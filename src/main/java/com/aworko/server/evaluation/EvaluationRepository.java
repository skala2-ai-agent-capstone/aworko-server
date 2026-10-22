package com.aworko.server.evaluation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationRepository extends JpaRepository<Evaluation, String> {

    Optional<Evaluation> findBySourceKey(String sourceKey);

    Optional<Evaluation> findByEvaluationIdAndCompanyId(String evaluationId, String companyId);

    List<Evaluation> findByWorkIdOrderByCreatedAtDesc(String workId);

    /** 상태 전이 전에 행을 잠근다. Agent가 business-mcp로 같은 행을 갱신할 수 있기 때문이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Evaluation e where e.evaluationId = :id and e.companyId = :companyId")
    Optional<Evaluation> findByIdForUpdate(String id, String companyId);

    /** 내 업무의 평가 목록(최신순) */
    @Query("select e from Evaluation e where e.companyId = :companyId and e.workId in "
            + "(select w.workId from WorkCase w where w.employeeId = :employeeId) order by e.createdAt desc")
    List<Evaluation> findMine(String companyId, String employeeId);
}
