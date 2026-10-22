package com.aworko.server.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Agent가 business-mcp save_evaluation_draft로 저장한 평가 초안.
 * items[]: {item_id, item, status(채점|미관측|검토 필요), score, reason, evidence_ids[]}
 */
@Entity
@Immutable
@Table(name = "evaluation_drafts")
@IdClass(RevisionKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationDraft {

    @Id
    @Column(name = "evaluation_id")
    private String evaluationId;

    @Id
    @Column(name = "revision")
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode items;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;

    @Column(name = "run_id")
    private String runId;
}
