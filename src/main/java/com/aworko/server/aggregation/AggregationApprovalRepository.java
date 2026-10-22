package com.aworko.server.aggregation;

import com.aworko.server.work.WorkType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AggregationApprovalRepository extends JpaRepository<AggregationApproval, Long> {

    List<AggregationApproval> findByTeamIdAndWorkTypeOrderByApprovedAtDesc(String teamId, WorkType workType);
}
