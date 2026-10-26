package com.aworko.server.evaluation;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.work.WorkCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 사람의 결정을 평가 기록에 쓴다: 이의 제기 → 검토 요청, 팀장 수정 → 새 초안.
 * 예전에는 RESUME 메시지로 Agent가 하던 일이다. 이제 Agent는 평가 초안·코칭 저장까지만 하고(backend-v1),
 * 상태 전환과 함께 백엔드 트랜잭션에서 처리한다. 호출 전에 평가 행을 잠가 둔다.
 * review_requests·evaluation_drafts는 MCP 스키마 테이블이라 business-mcp writes.py와 같은 모양으로 넣는다.
 */
@Component
@RequiredArgsConstructor
public class ReviewRecords {

    static final String APPEAL_REASON = "직원 이의 제기";
    /** business-mcp checks.py SECURITY_ITEM. 0·2점만 줄 수 있다 */
    static final String SECURITY_ITEM = "보안 준수";
    static final String SCORED = "채점";

    private final EvaluationDraftRepository draftRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 이의 제기 항목으로 검토 요청을 만들고 업무 담당 팀의 팀장에게 배정한다(팀장이 없으면 미배정).
     * targetItems는 item_id나 항목 이름. 최신 초안에 있는 항목만 받는다.
     * @return review_request_id
     */
    public String createAppealReview(Evaluation evaluation, WorkCase work, List<String> targetItems,
                                     List<String> citedEvidence) {
        ArrayNode items = latestItems(evaluation.getEvaluationId());
        Map<String, String> idOf = new HashMap<>();
        items.forEach(i -> {
            idOf.put(i.path("item_id").asText(), i.path("item_id").asText());
            idOf.put(i.path("item").asText(), i.path("item_id").asText());
        });
        Set<String> normalized = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String target : targetItems) {
            String id = idOf.get(target);
            if (id == null) {
                unknown.add(target);
            } else {
                normalized.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "평가에 없는 항목입니다: " + unknown);
        }

        ObjectNode memo = objectMapper.createObjectNode();
        memo.set("cited_evidence", objectMapper.valueToTree(citedEvidence == null ? List.of() : citedEvidence));
        String reviewRequestId = "RVQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO review_requests (review_request_id, evaluation_id, target_items, reason, review_memo, "
                        + "reviewer_id) VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?)",
                reviewRequestId, evaluation.getEvaluationId(), json(objectMapper.valueToTree(normalized)),
                APPEAL_REASON, json(memo), reviewerOf(work));
        return reviewRequestId;
    }

    /**
     * 팀장이 고친 점수로 새 초안 판(revision)을 만든다. 고친 항목은 '채점'으로 바꾸고 이전 값을 review_override에 남긴다.
     * 근거(evidence_ids)와 코칭은 그대로 둔다.
     * @return 새 draft revision
     */
    public int saveReviewerDraft(Evaluation evaluation, Map<String, Integer> newScores, String reviewRequestId,
                                 String reviewerId, String note) {
        ArrayNode items = latestItems(evaluation.getEvaluationId()).deepCopy();
        Map<String, Integer> pending = new HashMap<>(newScores);
        for (JsonNode node : items) {
            ObjectNode item = (ObjectNode) node;
            Integer score = pending.remove(item.path("item_id").asText());
            if (score == null) {
                continue;
            }
            Set<Integer> allowed = SECURITY_ITEM.equals(item.path("item").asText()) ? Set.of(0, 2) : Set.of(0, 1, 2);
            if (!allowed.contains(score)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        item.path("item_id").asText() + " 점수는 " + allowed.stream().sorted().toList() + " 중 하나입니다");
            }
            ObjectNode previous = objectMapper.createObjectNode();
            previous.set("status", item.get("status"));
            previous.set("score", item.get("score"));
            previous.set("reason", item.get("reason"));
            ObjectNode override = objectMapper.createObjectNode();
            override.put("review_request_id", reviewRequestId);
            override.put("reviewer_id", reviewerId);
            override.set("previous", previous);
            item.put("status", SCORED);
            item.put("score", score);
            item.put("reason", note == null || note.isBlank() ? "검토자가 점수를 수정했습니다" : note);
            item.set("review_override", override);
        }
        if (!pending.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "평가에 없는 항목입니다: " + pending.keySet());
        }
        return jdbc.queryForObject("INSERT INTO evaluation_drafts (evaluation_id, revision, items) "
                        + "SELECT ?, COALESCE(MAX(revision), 0) + 1, ?::jsonb FROM evaluation_drafts WHERE evaluation_id = ? "
                        + "RETURNING revision",
                Integer.class, evaluation.getEvaluationId(), json(items), evaluation.getEvaluationId());
    }

    private ArrayNode latestItems(String evaluationId) {
        return draftRepository.findFirstByEvaluationIdOrderByRevisionDesc(evaluationId)
                .map(EvaluationDraft::getItems)
                .filter(JsonNode::isArray)
                .map(ArrayNode.class::cast)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "평가 초안이 아직 없습니다"));
    }

    /** business-mcp request_review와 같은 규칙: 업무 담당 팀의 팀장 중 user_id가 가장 앞선 사람 */
    private String reviewerOf(WorkCase work) {
        if (work.getTeamId() == null) {
            return null;
        }
        return jdbc.queryForList("SELECT user_id FROM users WHERE role = 'team_lead' AND company_id = ? AND team_id = ? "
                        + "ORDER BY user_id LIMIT 1", String.class, work.getCompanyId(), work.getTeamId())
                .stream().findFirst().orElse(null);
    }

    private String json(JsonNode node) {
        return node.toString();
    }
}
