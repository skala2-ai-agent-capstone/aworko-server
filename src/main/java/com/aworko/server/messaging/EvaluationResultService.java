package com.aworko.server.messaging;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationCoachingRepository;
import com.aworko.server.evaluation.EvaluationDraftRepository;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.evaluation.RevisionKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최종 평가 완료 알림(FINAL_EVALUATION_SAVED) 적용. Agent Worker(backend-v1)가 초안·코칭을 business-mcp에 저장한 뒤 보낸다.
 * 알림은 "초안·코칭 저장 완료"만 뜻한다. 최종 확정이 아니며, review_required는 본인 화면의 검토 필요 표시일 뿐
 * 검토 요청을 자동으로 만들라는 뜻이 아니다 (ai-agent-capstone docs/backend_worker_alignment.md 2절).
 * <ol>
 *   <li>평가 행을 잠근 뒤 message_id로 중복을 거른다(재전달마다 같은 ID)</li>
 *   <li>영수증 확인: 알림의 draft_revision·coaching_revision이 DB에 있어야 한다</li>
 *   <li>처리 중(대기·진행 4단계)일 때만 '확인 대기'로 바꾼다. 이미 더 뒤 상태면 되돌리지 않는다</li>
 * </ol>
 * 결과는 evaluation_result_receipts에 남긴다(V5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationResultService {

    public enum Outcome {
        APPLIED, STALE, RECEIPT_MISMATCH, DUPLICATE, UNKNOWN_EVALUATION
    }

    /** 검증을 통과한 알림 본문 */
    public record Saved(String messageId, String evaluationId, String operationId, boolean reviewRequired,
                        int draftRevision, int coachingRevision) {

        static final String TYPE = "FINAL_EVALUATION_SAVED";
        static final String STATUS = "draft_and_coaching_saved";

        /** Agent FinalEvaluationSaved 계약과 다르면 empty (형식이 깨진 메시지는 다시 받아도 처리할 수 없다) */
        public static Optional<Saved> parse(JsonNode m) {
            if (m == null || !TYPE.equals(m.path("type").asText()) || !STATUS.equals(m.path("status").asText())
                    || !"1".equals(m.path("schema_version").asText())
                    || !m.path("review_required").isBoolean()
                    || !m.path("draft_revision").isInt() || !m.path("coaching_revision").isInt()) {
                return Optional.empty();
            }
            String messageId = m.path("message_id").asText("");
            String evaluationId = m.path("evaluation_id").asText("");
            String operationId = m.path("operation_id").asText("");
            int draft = m.path("draft_revision").asInt();
            int coaching = m.path("coaching_revision").asInt();
            if (messageId.isBlank() || evaluationId.isBlank() || operationId.isBlank() || draft < 1 || coaching < 1) {
                return Optional.empty();
            }
            return Optional.of(new Saved(messageId, evaluationId, operationId, m.path("review_required").asBoolean(),
                    draft, coaching));
        }
    }

    private final EvaluationRepository evaluationRepository;
    private final EvaluationDraftRepository draftRepository;
    private final EvaluationCoachingRepository coachingRepository;
    private final JdbcTemplate jdbc;

    @Transactional
    public Outcome apply(Saved saved) {
        Optional<Evaluation> found = evaluationRepository.findByIdForUpdate(saved.evaluationId());
        if (found.isEmpty()) {
            log.warn("Final evaluation result for unknown evaluation dropped: {}", saved.evaluationId());
            return Outcome.UNKNOWN_EVALUATION;
        }
        // 같은 평가의 알림은 위 행 잠금으로 줄을 서므로, 여기서 본 기록이 곧 중복 여부다
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM evaluation_result_receipts WHERE message_id = ?",
                Integer.class, saved.messageId());
        if (seen != null && seen > 0) {
            return Outcome.DUPLICATE;
        }

        Evaluation evaluation = found.get();
        Outcome outcome;
        if (!draftRepository.existsById(new RevisionKey(saved.evaluationId(), saved.draftRevision()))
                || !coachingRepository.existsById(new RevisionKey(saved.evaluationId(), saved.coachingRevision()))) {
            log.warn("Final evaluation result does not match stored revisions: evaluation_id={}, draft={}, coaching={}",
                    saved.evaluationId(), saved.draftRevision(), saved.coachingRevision());
            outcome = Outcome.RECEIPT_MISMATCH;
        } else if (EvaluationStatus.IN_PROGRESS.contains(evaluation.getStatus())) {
            evaluation.transition(EvaluationStatus.IN_PROGRESS, EvaluationStatus.AWAITING_CONFIRMATION);
            outcome = Outcome.APPLIED;
        } else {
            log.info("Final evaluation result left as is: evaluation_id={}, status={}", saved.evaluationId(),
                    evaluation.getStatus().getCode());
            outcome = Outcome.STALE;
        }
        jdbc.update("INSERT INTO evaluation_result_receipts (message_id, evaluation_id, operation_id, draft_revision, "
                        + "coaching_revision, review_required, outcome) VALUES (?, ?, ?, ?, ?, ?, ?)",
                saved.messageId(), saved.evaluationId(), saved.operationId(), saved.draftRevision(),
                saved.coachingRevision(), saved.reviewRequired(), outcome.name());
        return outcome;
    }
}
