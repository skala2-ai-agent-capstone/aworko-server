package com.aworko.server.work;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.error.RateLimitException;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.work.dto.LiveEvaluationResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실시간 피드백 요청. business-mcp가 최근 완료 질문·응답 최대 5쌍의 범위와 대화 스냅샷을 고정해 live_evaluations(대기)를
 * 만들면, 그 범위로 LIVE_EVALUATE를 아웃박스에 기록한다. 결과는 Agent가 business-mcp로 같은 행에 채우며,
 * 프론트는 조회 API를 폴링한다. 사용자별 5분에 1회.
 */
@Slf4j
@Service
public class LiveEvaluationService {

    private final WorkCaseRepository workCaseRepository;
    private final LiveEvaluationRepository liveEvaluationRepository;
    private final LiveRequestGateway liveRequestGateway;
    private final LiveFeedbackDisplays displays;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final Duration minInterval;

    public LiveEvaluationService(WorkCaseRepository workCaseRepository,
                                 LiveEvaluationRepository liveEvaluationRepository,
                                 LiveRequestGateway liveRequestGateway, LiveFeedbackDisplays displays,
                                 OutboxPublisher outboxPublisher, Clock clock,
                                 @Value("${app.live-evaluation.min-interval:5m}") Duration minInterval) {
        this.workCaseRepository = workCaseRepository;
        this.liveEvaluationRepository = liveEvaluationRepository;
        this.liveRequestGateway = liveRequestGateway;
        this.displays = displays;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.minInterval = minInterval;
    }

    /**
     * 요청 행은 MCP가 자기 트랜잭션으로 먼저 커밋한다. 이후 아웃박스 기록이 실패하면 행이 대기로 남으므로
     * (드묾) 사용자는 5분 뒤 다시 요청해야 한다.
     */
    @Transactional
    public LiveEvaluationResponse request(AuthPrincipal me, String workId) {
        WorkCase work = workCaseRepository.findByWorkIdAndCompanyId(workId, me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "업무를 찾을 수 없습니다"));
        if (!work.getEmployeeId().equals(me.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 업무만 피드백을 요청할 수 있습니다");
        }
        checkRateLimit(me.userId(), OffsetDateTime.now(clock));

        LiveRequestGateway.LiveRequest created;
        try {
            created = liveRequestGateway.create(workId, me.userId());
        } catch (LiveRequestGateway.Rejected e) {
            throw translate(e);
        }
        outboxPublisher.liveEvaluate(created.liveEvalId(), me.companyId(), workId, me.userId(), created.fromSeq(),
                created.toSeq(), created.rubricVersion());
        LiveEvaluation live = liveEvaluationRepository.findById(created.liveEvalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "피드백 요청을 찾을 수 없습니다"));
        return LiveEvaluationResponse.of(live, null);
    }

    @Transactional(readOnly = true)
    public LiveEvaluationResponse get(AuthPrincipal me, String liveEvalId) {
        LiveEvaluation live = liveEvaluationRepository.findById(liveEvalId)
                .filter(l -> workCaseRepository.findByWorkIdAndCompanyId(l.getWorkId(), me.companyId()).isPresent())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "피드백 요청을 찾을 수 없습니다"));
        if (!live.getRequestedBy().equals(me.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 요청한 피드백만 볼 수 있습니다");
        }
        return LiveEvaluationResponse.of(live, displays.of(live));
    }

    void checkRateLimit(String userId, OffsetDateTime now) {
        liveEvaluationRepository.findFirstByRequestedByOrderByRequestedAtDesc(userId).ifPresent(last -> {
            Duration elapsed = Duration.between(last.getRequestedAt(), now);
            if (elapsed.compareTo(minInterval) < 0) {
                long retryAfter = Math.max(1, minInterval.minus(elapsed).toSeconds());
                throw new RateLimitException(
                        "실시간 피드백은 " + minInterval.toMinutes() + "분에 한 번 요청할 수 있어요", retryAfter);
            }
        });
    }

    /** 권한은 위에서 이미 확인했으므로 MCP의 FORBIDDEN은 내부 키 설정 오류다 */
    BusinessException translate(LiveRequestGateway.Rejected e) {
        return switch (e.code()) {
            case "VALIDATION_ERROR" -> new BusinessException(ErrorCode.CONFLICT, e.getMessage());
            case "NOT_FOUND" -> new BusinessException(ErrorCode.NOT_FOUND, e.getMessage());
            case "RATE_LIMITED" -> new RateLimitException(e.getMessage(), minInterval.toSeconds());
            default -> {
                log.error("business-mcp rejected live request: code={}", e.code());
                yield new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "피드백 요청을 만들지 못했습니다");
            }
        };
    }
}
