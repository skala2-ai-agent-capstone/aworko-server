package com.aworko.server.work;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveEvaluationRepository extends JpaRepository<LiveEvaluation, String> {

    /** 사용자별 5분 1회 제한 판단용 */
    Optional<LiveEvaluation> findFirstByRequestedByOrderByRequestedAtDesc(String requestedBy);
}
