package com.aworko.server.humanaction;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사람의 행동 기록(본인 확인·이의 제기·검토 결정·집계 승인 등). business-mcp가 이의·검토 결정을 여기서 읽는다.
 * payload는 원문, payload_masked는 policy_rules 마스킹본이다 (MCP 응답에는 마스킹본만 나간다).
 */
@Entity
@Table(name = "human_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HumanAction {

    @Id
    @Column(name = "action_id")
    private String actionId;

    @Column(name = "work_id", nullable = false)
    private String workId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String action;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    @Column(name = "artifact_version")
    private String artifactVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_masked", nullable = false)
    private JsonNode payloadMasked;

    public static HumanAction record(String workId, String userId, HumanActionType type, String artifactVersion,
                                     JsonNode payload, JsonNode payloadMasked) {
        HumanAction a = new HumanAction();
        a.actionId = "HA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        a.workId = workId;
        a.userId = userId;
        a.action = type.getCode();
        a.at = OffsetDateTime.now();
        a.artifactVersion = artifactVersion;
        a.payload = payload;
        a.payloadMasked = payloadMasked;
        return a;
    }
}
