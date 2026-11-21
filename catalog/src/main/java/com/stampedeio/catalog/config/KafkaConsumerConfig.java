package com.stampedeio.catalog.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import com.stampedeio.catalog.projection.NonRetryableProjectionException;

import io.micrometer.core.instrument.MeterRegistry;

@EnableKafka
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "spring.kafka.bootstrap-servers")
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${catalog.kafka.retry.initial-interval:1000}")
    private long retryInitialInterval;

    @Value("${catalog.kafka.retry.multiplier:2.0}")
    private double retryMultiplier;

    @Value("${catalog.kafka.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Bean
    public ConsumerFactory<String, Map<String, Object>> consumerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "catalog-availability-projector");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // STAM-452: a record whose bytes aren't JSON fails inside poll(),
        // before any listener or error handler sees it, and the container
        // re-polls that offset forever. The wrapper turns the failure into a
        // DeserializationException the DefaultErrorHandler can dead-letter.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");
        DefaultKafkaConsumerFactory<String, Map<String, Object>> factory = new DefaultKafkaConsumerFactory<>(props);
        // STAM-65 / AC2: a manually-built factory doesn't get Spring
        // Boot's auto-configured Micrometer listener — without this,
        // zero kafka_consumer_* metrics exist at all.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ProducerFactory<String, Object> dlqProducerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // A record that failed deserialization reaches the recoverer as its
        // original byte[]; publish those bytes as-is rather than as a
        // base64 JSON string, so the DLQ holds exactly what arrived.
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>());
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new DelegatingByTypeSerializer(byType, true));
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> dlqKafkaTemplate(ProducerFactory<String, Object> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> dlqKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dlqKafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
        recoverer.setHeadersFunction((consumerRecord, thrown) -> {
            // Spring wraps every failure in ListenerExecutionFailedException;
            // record the cause underneath, or the DLQ can't tell a
            // DeserializationException from a projection bug.
            Exception ex = thrown instanceof ListenerExecutionFailedException
                    && thrown.getCause() instanceof Exception cause ? cause : thrown;
            RecordHeaders headers = new RecordHeaders();
            headers.add("original-topic",
                    consumerRecord.topic().getBytes(StandardCharsets.UTF_8));
            headers.add("exception-class",
                    ex.getClass().getName().getBytes(StandardCharsets.UTF_8));
            headers.add("exception-message",
                    (ex.getMessage() != null ? ex.getMessage() : "null").getBytes(StandardCharsets.UTF_8));
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            headers.add("stack-trace",
                    sw.toString().getBytes(StandardCharsets.UTF_8));
            return headers;
        });
        return recoverer;
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retryInitialInterval);
        backOff.setMultiplier(retryMultiplier);
        backOff.setMaxAttempts(retryMaxAttempts);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(NonRetryableProjectionException.class);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory(
            ConsumerFactory<String, Map<String, Object>> consumerFactory, CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // STAM-406: populates MDC correlationId/service from the record's
        // Kafka headers before the listener method runs.
        factory.setRecordInterceptor(new KafkaCorrelationIdInterceptor());
        return factory;
    }
}
