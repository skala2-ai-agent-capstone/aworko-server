package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.error.RateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveEvaluationRateLimitTest {

    @Mock LiveEvaluationRepository liveRepo;

    LiveEvaluationService service() {
        return new LiveEvaluationService(null, liveRepo, null, null, null, Clock.systemDefaultZone(), Duration.ofMinutes(5));
    }

    @Test
    void mcpRejectionsMapToBackendErrors() {
        assertThat(service().translate(new LiveRequestGateway.Rejected("VALIDATION_ERROR", "완료된 질문·응답이 없음")))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT)
                .hasMessage("완료된 질문·응답이 없음");
        assertThat(service().translate(new LiveRequestGateway.Rejected("RATE_LIMITED", "5분에 한 번")))
                .isInstanceOf(RateLimitException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 300L);
        // 권한은 백엔드가 먼저 확인하므로 MCP의 FORBIDDEN은 내부 키 설정 오류로 본다
        assertThat(service().translate(new LiveRequestGateway.Rejected("FORBIDDEN", "X-Internal-Key가 필요합니다")))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void secondRequestWithinFiveMinutesIsRejectedWithRemainingSeconds() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T10:05:00+09:00");
        when(liveRepo.findFirstByRequestedByOrderByRequestedAtDesc("E-1"))
                .thenReturn(Optional.of(LiveEvaluation.request("W", "E-1", 1, 5, null, null, now.minusMinutes(2))));

        assertThatThrownBy(() -> service().checkRateLimit("E-1", now))
                .isInstanceOf(RateLimitException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 180L);
    }

    @Test
    void requestAfterFiveMinutesOrFirstRequestIsAllowed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T10:05:00+09:00");
        when(liveRepo.findFirstByRequestedByOrderByRequestedAtDesc("E-1"))
                .thenReturn(Optional.of(LiveEvaluation.request("W", "E-1", 1, 5, null, null, now.minusMinutes(5))));
        when(liveRepo.findFirstByRequestedByOrderByRequestedAtDesc("E-2")).thenReturn(Optional.empty());

        assertThatCode(() -> service().checkRateLimit("E-1", now)).doesNotThrowAnyException();
        assertThatCode(() -> service().checkRateLimit("E-2", now)).doesNotThrowAnyException();
    }
}
