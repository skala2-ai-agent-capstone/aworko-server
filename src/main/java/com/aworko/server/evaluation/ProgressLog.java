package com.aworko.server.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 노드 진행 기록. Agent가 business-mcp update_progress로 쓰고, 백엔드는 읽어서 SSE로 전달한다. */
@Entity
@Immutable
@Table(name = "progress_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProgressLog {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "evaluation_id", nullable = false)
    private String evaluationId;

    @Column(nullable = false)
    private String stage;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    @Column(name = "run_id")
    private String runId;
}
