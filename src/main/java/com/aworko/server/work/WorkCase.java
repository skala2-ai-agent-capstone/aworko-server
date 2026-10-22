package com.aworko.server.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 업무 건. 원문(registration 등)은 매핑하지 않는다: 백엔드는 식별·권한·상태 판단에 필요한 컬럼만 읽는다.
 */
@Entity
@Immutable
@Table(name = "work_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkCase {

    @Id
    @Column(name = "work_id")
    private String workId;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "team_id")
    private String teamId;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column
    private String title;

    /** 업무 생성 시에는 비어 있고 평가 때 채워진다. */
    @Column(name = "work_type")
    private WorkType workType;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
