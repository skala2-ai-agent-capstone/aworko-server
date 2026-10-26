package com.aworko.server.evaluation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
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

    /** 큐 메시지 처리용 잠금. 메시지에는 회사가 없으므로 평가 ID로만 찾는다 (최종 평가 완료 알림) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Evaluation e where e.evaluationId = :id")
    Optional<Evaluation> findByIdForUpdate(String id);

    /**
     * 실행 토큰 공급용: 상태가 statuses이고 EVALUATE를 보낸 평가 건, 오래된 것부터.
     * 시드로만 들어온 평가 건은 큐 메시지가 없어 Worker가 받지 않으므로 뺀다.
     */
    @Query("select e from Evaluation e where e.status in :statuses and exists (select 1 from OutboxEvent o "
            + "where o.type = 'EVALUATE' and o.aggregateId = e.evaluationId) order by e.createdAt")
    List<Evaluation> findQueuedByStatusIn(Collection<EvaluationStatus> statuses);

    /** 내 업무의 평가 목록(최신순) */
    @Query("select e from Evaluation e where e.companyId = :companyId and e.workId in "
            + "(select w.workId from WorkCase w where w.employeeId = :employeeId) order by e.createdAt desc")
    List<Evaluation> findMine(String companyId, String employeeId);
}
