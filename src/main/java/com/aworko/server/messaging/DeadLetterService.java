package com.aworko.server.messaging;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent가 재시도 한도를 넘겨 DLQ로 보낸 메시지를 실패 상태로 기록한다.
 * 상태를 "처리 실패/실패"로 바꾸는 것은 서버 전용 규칙이므로 백엔드가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterService {

    static final String DEFAULT_REASON = "재시도 한도 초과";

    private final EvaluationRepository evaluationRepository;
    private final EvaluationFailureRepository failureRepository;
    private final LiveEvaluationRepository liveEvaluationRepository;
    private final OutboxEventRepository outboxRepository;

    /**
     * 평가 메시지(EVALUATE): 평가 건을 '처리 실패'로 바꾸고 실패 기록을 남긴다. 결과 알림 처리와 겹치지 않게 행을 잠근다.
     * 이미 결과가 저장된 건(확인 대기 등)이나, 재처리로 새 요청을 보낸 뒤 늦게 온 이전 요청의 실패는 상태를 바꾸지 않는다.
     */
    @Transactional
    public void recordEvaluationFailure(JsonNode message, String reason) {
        String evaluationId = text(message, "evaluation_id").orElse(null);
        if (evaluationId == null) {
            log.warn("Evaluation DLQ message without evaluation_id dropped: message_id={}", text(message, "message_id"));
            return;
        }
        Optional<Evaluation> evaluation = evaluationRepository.findByIdForUpdate(evaluationId);
        if (evaluation.isEmpty()) {
            log.warn("Evaluation DLQ message for unknown evaluation dropped: {}", evaluationId);
            return;
        }
        String messageId = text(message, "message_id").orElse(null);
        if (messageId != null && outboxRepository.findEvaluateMessageIds(evaluationId).indexOf(messageId) > 0) {
            log.info("Evaluation DLQ message for an earlier request ignored: evaluation_id={}, message_id={}",
                    evaluationId, messageId);
            return;
        }
        if (!evaluation.get().markFailed()) {
            log.info("Evaluation DLQ message left status as is: evaluation_id={}, status={}", evaluationId,
                    evaluation.get().getStatus().getCode());
            return;
        }
        failureRepository.save(EvaluationFailure.of(evaluationId, message, reasonOrDefault(reason)));
    }

    /** 실시간 피드백 메시지(LIVE_EVALUATE): 요청을 '실패'로 바꾼다. */
    @Transactional
    public void recordLiveEvaluationFailure(JsonNode message, String reason) {
        String liveEvalId = text(message, "live_eval_id").orElse(null);
        if (liveEvalId == null) {
            log.warn("Live evaluation DLQ message without live_eval_id dropped: message_id={}",
                    text(message, "message_id"));
            return;
        }
        liveEvaluationRepository.findById(liveEvalId).ifPresentOrElse(
                live -> live.markFailed(reasonOrDefault(reason)),
                () -> log.warn("Live evaluation DLQ message for unknown request dropped: {}", liveEvalId));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? Optional.empty() : Optional.of(v.asText());
    }

    private static String reasonOrDefault(String reason) {
        return reason == null || reason.isBlank() ? DEFAULT_REASON : reason;
    }
}
