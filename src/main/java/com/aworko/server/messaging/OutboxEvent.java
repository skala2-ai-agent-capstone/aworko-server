package com.aworko.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 평가 메시지 발행 대기열 (transactional outbox). V1__backend_tables.sql */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    /** EVALUATE / RESUME */
    @Column(nullable = false)
    private String type;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public static OutboxEvent pending(String aggregateId, String type, String routingKey, JsonNode payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateId = aggregateId;
        e.type = type;
        e.routingKey = routingKey;
        e.payload = payload;
        e.status = PENDING;
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    public void markSent() {
        this.status = SENT;
        this.sentAt = OffsetDateTime.now();
        this.attempts++;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error;
    }
}
