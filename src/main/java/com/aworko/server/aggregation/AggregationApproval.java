package com.aworko.server.aggregation;

import com.aworko.server.work.WorkType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 팀장의 팀 집계 승인. snapshot은 승인 시점의 집계 결과. V1__backend_tables.sql */
@Entity
@Table(name = "aggregation_approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AggregationApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "team_id", nullable = false)
    private String teamId;

    @Column(name = "work_type", nullable = false)
    private WorkType workType;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "approved_by", nullable = false)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false)
    private OffsetDateTime approvedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode snapshot;

    public static AggregationApproval of(String companyId, String teamId, WorkType workType, LocalDate from,
                                         LocalDate to, String approvedBy, JsonNode snapshot) {
        AggregationApproval a = new AggregationApproval();
        a.companyId = companyId;
        a.teamId = teamId;
        a.workType = workType;
        a.periodFrom = from;
        a.periodTo = to;
        a.approvedBy = approvedBy;
        a.approvedAt = OffsetDateTime.now();
        a.snapshot = snapshot;
        return a;
    }
}
