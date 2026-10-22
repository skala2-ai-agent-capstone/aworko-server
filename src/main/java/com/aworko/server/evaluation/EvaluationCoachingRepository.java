package com.aworko.server.evaluation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationCoachingRepository extends JpaRepository<EvaluationCoaching, RevisionKey> {

    Optional<EvaluationCoaching> findFirstByEvaluationIdOrderByRevisionDesc(String evaluationId);
}
