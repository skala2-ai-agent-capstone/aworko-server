package com.aworko.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지를 바로 보내지 않고 outbox_events에 쌓는다. 호출한 쪽의 트랜잭션(평가 생성·상태 변경)과 함께 커밋되므로
 * "DB에는 저장됐는데 메시지는 안 나감 / 메시지는 나갔는데 DB는 롤백됨"이 생기지 않는다. 실제 발행은 OutboxRelay가 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public EvaluationMessages.Evaluate evaluate(String evaluationId, String companyId, String workId,
                                                String artifactVersion, String rubricVersion) {
        var msg = new EvaluationMessages.Evaluate(newMessageId(), MessageType.EVALUATE.name(), evaluationId,
                companyId, workId, artifactVersion, rubricVersion, 0);
        save(MessageType.EVALUATE, evaluationId, msg);
        return msg;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EvaluationMessages.LiveEvaluate liveEvaluate(String liveEvalId, String companyId, String workId,
                                                        String requestedBy, int fromSeq, int toSeq,
                                                        String rubricVersion) {
        var msg = new EvaluationMessages.LiveEvaluate(newMessageId(), MessageType.LIVE_EVALUATE.name(), liveEvalId,
                companyId, workId, requestedBy, fromSeq, toSeq, rubricVersion, 0);
        save(MessageType.LIVE_EVALUATE, liveEvalId, msg);
        return msg;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EvaluationMessages.RubricGenerate rubricGenerate(String generationId, String companyId, String policyDocId,
                                                            String policyVersion, List<String> workTypes,
                                                            String requestedBy) {
        var msg = new EvaluationMessages.RubricGenerate(newMessageId(), MessageType.RUBRIC_GENERATE.name(), generationId,
                companyId, policyDocId, policyVersion, workTypes, requestedBy, 0);
        save(MessageType.RUBRIC_GENERATE, generationId, msg);
        return msg;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EvaluationMessages.TeamEvaluate teamEvaluate(String requestId, String companyId) {
        var msg = new EvaluationMessages.TeamEvaluate(newMessageId(), MessageType.TEAM_EVALUATE.name(), requestId,
                companyId, 0);
        save(MessageType.TEAM_EVALUATE, requestId, msg);
        return msg;
    }

    private void save(MessageType type, String aggregateId, Object body) {
        JsonNode payload = objectMapper.valueToTree(body);
        outboxRepository.save(OutboxEvent.pending(aggregateId, type.name(), type.getRoutingKey(), payload));
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
