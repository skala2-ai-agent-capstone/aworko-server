package com.aworko.server.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 아웃박스 릴레이 주기 실행. app.messaging.outbox.enabled=false면 등록하지 않는다(테스트에서 직접 호출). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.messaging.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "${app.messaging.outbox.poll-interval}")
    public void run() {
        try {
            relay.relayOnce();
        } catch (RuntimeException e) {
            // DB·브로커가 잠깐 끊겨도 스케줄러는 계속 돈다
            log.warn("Outbox relay run failed", e);
        }
    }
}
