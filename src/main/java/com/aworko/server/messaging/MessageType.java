package com.aworko.server.messaging;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 아웃박스 메시지 종류 → 발행할 exchange·routing key. outbox_events.type 값과 같다. */
@Getter
@RequiredArgsConstructor
public enum MessageType {

    EVALUATE(Topology.EVALUATION_EXCHANGE, Topology.RK_EVALUATE),
    LIVE_EVALUATE(Topology.LIVE_EXCHANGE, Topology.RK_LIVE_EVALUATE),
    RUBRIC_GENERATE(Topology.RUBRIC_EXCHANGE, Topology.RK_RUBRIC_GENERATE),
    TEAM_EVALUATE(Topology.TEAM_EXCHANGE, Topology.RK_TEAM_EVALUATE);

    private final String exchange;
    private final String routingKey;
}
