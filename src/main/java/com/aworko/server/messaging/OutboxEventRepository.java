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
}
