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
 * {exchange} ──evaluate/resume──▶ requests ──(Agent 소비)
 *            ──*.retry──────────▶ retry (TTL) ──만료──▶ {exchange}/evaluate → requests
 *            ──*.dlq────────────▶ dlq ──(백엔드 DlqListener)
 * </pre>
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
        return new Declarables(exchange, requests, retry, dlq,
                bind(requests, exchange, RK_EVALUATE),
                bind(requests, exchange, RK_RESUME),
                bind(retry, exchange, RK_EVALUATION_RETRY),
                bind(dlq, exchange, RK_EVALUATION_DLQ));
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
