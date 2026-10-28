package com.aworko.server.messaging;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderById(String status, Limit limit);

    /** 백엔드를 여러 대 띄워도 같은 행을 두 번 발행하지 않도록 잠근 행은 건너뛴다. */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockPending(int limit);

    /** 평가 건에 보낸 EVALUATE의 message_id, 최근 것부터. DLQ로 돌아온 메시지가 이전 요청인지 가린다 */
    @Query(value = "SELECT payload->>'message_id' FROM outbox_events WHERE aggregate_id = :evaluationId "
            + "AND type = 'EVALUATE' ORDER BY id DESC", nativeQuery = true)
    List<String> findEvaluateMessageIds(String evaluationId);
}
