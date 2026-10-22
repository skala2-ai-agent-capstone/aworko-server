package com.aworko.server.evaluation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, String> {

    List<ReviewRequest> findByEvaluationIdOrderByCreatedAt(String evaluationId);

    List<ReviewRequest> findByReviewerIdOrderByCreatedAtDesc(String reviewerId);
}
