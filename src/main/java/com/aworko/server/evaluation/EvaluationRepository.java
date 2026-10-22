package com.aworko.server.evaluation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRepository extends JpaRepository<Evaluation, String> {

    Optional<Evaluation> findBySourceKey(String sourceKey);

    Optional<Evaluation> findByEvaluationIdAndCompanyId(String evaluationId, String companyId);

    List<Evaluation> findByWorkIdOrderByCreatedAtDesc(String workId);
}
