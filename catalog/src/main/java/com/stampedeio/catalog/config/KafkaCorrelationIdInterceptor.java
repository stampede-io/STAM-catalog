package com.stampedeio.catalog.config;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * STAM-406 / AC5: populates MDC correlationId/service from the record's
 * Kafka headers before the listener method runs, so the projection's log
 * lines are queryable by the same correlationId the request started with
 * on the other side of the queue boundary. See STAM-booking's identical
 * class (the producer side -- OutboxPublisher) for the header contract.
 */
public final class KafkaCorrelationIdInterceptor implements RecordInterceptor<String, Map<String, Object>> {

    public static final String HEADER = "correlationId";
    private static final String SERVICE = "catalog";

    @Override
    public ConsumerRecord<String, Map<String, Object>> intercept(
            ConsumerRecord<String, Map<String, Object>> record, Consumer<String, Map<String, Object>> consumer) {
        Header header = record.headers().lastHeader(HEADER);
        if (header != null) {
            MDC.put(HEADER, new String(header.value(), StandardCharsets.UTF_8));
        }
        MDC.put("service", SERVICE);
        return record;
    }

    @Override
    public void success(ConsumerRecord<String, Map<String, Object>> record,
                         Consumer<String, Map<String, Object>> consumer) {
        clear();
    }

    @Override
    public void failure(ConsumerRecord<String, Map<String, Object>> record, Exception exception,
                         Consumer<String, Map<String, Object>> consumer) {
        clear();
    }

    private void clear() {
        MDC.remove(HEADER);
        MDC.remove("service");
    }
}
