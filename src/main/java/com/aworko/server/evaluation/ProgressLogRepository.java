package com.aworko.server.evaluation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgressLogRepository extends JpaRepository<ProgressLog, Long> {

    List<ProgressLog> findByEvaluationIdOrderById(String evaluationId);

    List<ProgressLog> findByEvaluationIdAndIdGreaterThanOrderById(String evaluationId, Long afterId);
}
