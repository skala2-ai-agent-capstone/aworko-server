package com.aworko.server.global.config;

import static com.aworko.server.messaging.Topology.*;

import com.aworko.server.messaging.MessagingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 큐 토폴로지 선언. 백엔드가 기동 시 선언하고, Agent는 같은 이름·인자로 선언하거나 그대로 쓴다.
 *
 * <pre>
 * {exchange} ──evaluate──────▶ requests ──(Agent 소비)
 *            ──*.retry──────────▶ retry (TTL) ──만료──▶ {exchange}/evaluate → requests
 *            ──*.dlq────────────▶ dlq ──(백엔드 DlqListener)
 *            ──*.results────────▶ results ──(백엔드 EvaluationResultListener, 최종 평가)
 * </pre>
 * Agent Worker는 retry·dlq·results를 기본 exchange로 큐 이름에 직접 발행한다. 큐는 백엔드가 먼저 만들어 둬야 한다.
 * 예전 resume 바인딩(→ requests)은 선언하지 않는다. 이미 있는 브로커에 남아 있어도 resume으로 발행하는 곳은 없다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitConfig {

    @Bean
    public Declarables evaluationTopology(MessagingProperties props) {
        DirectExchange exchange = new DirectExchange(EVALUATION_EXCHANGE, true, false);
        Queue requests = QueueBuilder.durable(EVALUATION_REQUESTS).build();
        Queue retry = retryQueue(EVALUATION_RETRY, EVALUATION_EXCHANGE, RK_EVALUATE, props.evaluationRetryDelay());
        Queue dlq = QueueBuilder.durable(EVALUATION_DLQ).build();
        Queue results = QueueBuilder.durable(EVALUATION_RESULTS).build();
        return new Declarables(exchange, requests, retry, dlq, results,
                bind(requests, exchange, RK_EVALUATE),
                bind(retry, exchange, RK_EVALUATION_RETRY),
                bind(dlq, exchange, RK_EVALUATION_DLQ),
                bind(results, exchange, RK_EVALUATION_RESULTS));
    }

    @Bean
    public Declarables liveEvaluationTopology(MessagingProperties props) {
        DirectExchange exchange = new DirectExchange(LIVE_EXCHANGE, true, false);
        Queue requests = QueueBuilder.durable(LIVE_REQUESTS).build();
        Queue retry = retryQueue(LIVE_RETRY, LIVE_EXCHANGE, RK_LIVE_EVALUATE, props.liveEvaluationRetryDelay());
        Queue dlq = QueueBuilder.durable(LIVE_DLQ).build();
        return new Declarables(exchange, requests, retry, dlq,
                bind(requests, exchange, RK_LIVE_EVALUATE),
                bind(retry, exchange, RK_LIVE_RETRY),
                bind(dlq, exchange, RK_LIVE_DLQ));
    }

    /** 평가 기준 생성. 재시도 간격은 최종 평가와 같다. results는 Agent가 완료를 알리고 백엔드가 소비한다 */
    @Bean
    public Declarables rubricGenerationTopology(MessagingProperties props) {
        DirectExchange exchange = new DirectExchange(RUBRIC_EXCHANGE, true, false);
        Queue requests = QueueBuilder.durable(RUBRIC_REQUESTS).build();
        Queue retry = retryQueue(RUBRIC_RETRY, RUBRIC_EXCHANGE, RK_RUBRIC_GENERATE, props.evaluationRetryDelay());
        Queue dlq = QueueBuilder.durable(RUBRIC_DLQ).build();
        Queue results = QueueBuilder.durable(RUBRIC_RESULTS).build();
        return new Declarables(exchange, requests, retry, dlq, results,
                bind(requests, exchange, RK_RUBRIC_GENERATE),
                bind(retry, exchange, RK_RUBRIC_RETRY),
                bind(dlq, exchange, RK_RUBRIC_DLQ),
                bind(results, exchange, RK_RUBRIC_RESULTS));
    }

    /** 팀 평가. 재시도 간격은 최종 평가와 같다. 완료는 MCP(team_evaluations)에 남으므로 결과 큐가 없다 */
    @Bean
    public Declarables teamEvaluationTopology(MessagingProperties props) {
        DirectExchange exchange = new DirectExchange(TEAM_EXCHANGE, true, false);
        Queue requests = QueueBuilder.durable(TEAM_REQUESTS).build();
        Queue retry = retryQueue(TEAM_RETRY, TEAM_EXCHANGE, RK_TEAM_EVALUATE, props.evaluationRetryDelay());
        Queue dlq = QueueBuilder.durable(TEAM_DLQ).build();
        return new Declarables(exchange, requests, retry, dlq,
                bind(requests, exchange, RK_TEAM_EVALUATE),
                bind(retry, exchange, RK_TEAM_RETRY),
                bind(dlq, exchange, RK_TEAM_DLQ));
    }

    /** RabbitTemplate·@RabbitListener가 앱의 ObjectMapper(snake_case)로 JSON을 쓰고 읽게 한다. */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** 메시지가 TTL 동안 머물다 만료되면 dead-letter 설정에 따라 요청 큐로 돌아간다. */
    static Queue retryQueue(String name, String backToExchange, String backToRoutingKey, Duration delay) {
        return QueueBuilder.durable(name)
                .ttl((int) delay.toMillis())
                .deadLetterExchange(backToExchange)
                .deadLetterRoutingKey(backToRoutingKey)
                .build();
    }

    private static Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
