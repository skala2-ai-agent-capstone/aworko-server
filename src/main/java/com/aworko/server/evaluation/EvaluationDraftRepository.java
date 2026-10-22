package com.aworko.server.evaluation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationDraftRepository extends JpaRepository<EvaluationDraft, RevisionKey> {

    Optional<EvaluationDraft> findFirstByEvaluationIdOrderByRevisionDesc(String evaluationId);
}
