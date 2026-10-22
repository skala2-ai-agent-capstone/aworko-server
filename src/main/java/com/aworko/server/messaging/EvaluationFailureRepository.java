package com.aworko.server.messaging;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationFailureRepository extends JpaRepository<EvaluationFailure, Long> {

    List<EvaluationFailure> findByEvaluationIdAndReprocessedAtIsNull(String evaluationId);
}
