package com.aworko.server.work;

import com.aworko.server.document.Document;
import com.aworko.server.document.DocumentRepository;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.error.RateLimitException;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.work.dto.LiveEvaluationResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실시간 피드백 요청. 최근 대화 5개의 범위를 고정해 live_evaluations(대기)에 기록하고 LIVE_EVALUATE를 발행한다.
 * 결과는 Agent가 business-mcp로 같은 행에 채우며, 프론트는 조회 API를 폴링한다. 사용자별 5분에 1회.
 */
@Service
public class LiveEvaluationService {

    static final int RECENT_MESSAGES = 5;

    private final WorkCaseRepository workCaseRepository;
    private final ChatMessageRepository messageRepository;
    private final LiveEvaluationRepository liveEvaluationRepository;
    private final DocumentRepository documentRepository;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final Duration minInterval;

    public LiveEvaluationService(WorkCaseRepository workCaseRepository, ChatMessageRepository messageRepository,
                                 LiveEvaluationRepository liveEvaluationRepository,
                                 DocumentRepository documentRepository, OutboxPublisher outboxPublisher, Clock clock,
                                 @Value("${app.live-evaluation.min-interval:5m}") Duration minInterval) {
        this.workCaseRepository = workCaseRepository;
        this.messageRepository = messageRepository;
        this.liveEvaluationRepository = liveEvaluationRepository;
        this.documentRepository = documentRepository;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.minInterval = minInterval;
    }

    @Transactional
    public LiveEvaluationResponse request(AuthPrincipal me, String workId) {
        WorkCase work = workCaseRepository.findByWorkIdAndCompanyId(workId, me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "업무를 찾을 수 없습니다"));
        if (!work.getEmployeeId().equals(me.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 업무만 피드백을 요청할 수 있습니다");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        checkRateLimit(me.userId(), now);

        List<ChatMessage> recent = messageRepository.findTop5ByWorkIdOrderBySeqDesc(workId);
        if (recent.stream().noneMatch(m -> ChatMessage.ROLE_USER.equals(m.getRole()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "피드백할 대화가 없습니다");
        }
        int fromSeq = recent.stream().mapToInt(ChatMessage::getSeq).min().orElseThrow();
        int toSeq = recent.stream().mapToInt(ChatMessage::getSeq).max().orElseThrow();
        Optional<Document> rubric = Optional.ofNullable(work.getWorkType()).flatMap(t -> documentRepository
                .findByCompanyIdAndCollectionAndWorkTypeAndStatusOrderByEffectiveFromDesc(
                        me.companyId(), Document.COLLECTION_RUBRICS, t, Document.STATUS_CURRENT)
                .stream().findFirst());

        LiveEvaluation live = liveEvaluationRepository.save(LiveEvaluation.request(workId, me.userId(), fromSeq, toSeq,
                rubric.map(Document::getDocId).orElse(null), rubric.map(Document::getVersion).orElse(null), now));
        outboxPublisher.liveEvaluate(live.getLiveEvalId(), me.companyId(), workId, me.userId(), fromSeq, toSeq,
                live.getRubricVersion());
        return LiveEvaluationResponse.of(live);
    }

    @Transactional(readOnly = true)
    public LiveEvaluationResponse get(AuthPrincipal me, String liveEvalId) {
        LiveEvaluation live = liveEvaluationRepository.findById(liveEvalId)
                .filter(l -> workCaseRepository.findByWorkIdAndCompanyId(l.getWorkId(), me.companyId()).isPresent())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "피드백 요청을 찾을 수 없습니다"));
        if (!live.getRequestedBy().equals(me.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 요청한 피드백만 볼 수 있습니다");
        }
        return LiveEvaluationResponse.of(live);
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
}
