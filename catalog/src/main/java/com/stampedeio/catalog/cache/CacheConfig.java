package com.stampedeio.catalog.cache;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import com.stampedeio.catalog.api.dto.SeatResponse;

@Configuration
public class CacheConfig {

    public static final String AVAILABILITY_CACHE = "availability";

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${catalog.cache.availability-ttl-seconds:30}") long availabilityTtlSeconds) {

        ObjectMapper mapper = new ObjectMapper();
        CollectionType listType = mapper.getTypeFactory()
                .constructCollectionType(java.util.List.class, SeatResponse.class);
        Jackson2JsonRedisSerializer<Object> serializer =
                new Jackson2JsonRedisSerializer<>(mapper, listType);

        RedisCacheConfiguration availabilityConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(availabilityTtlSeconds))
                .disableCachingNullValues()
                .computePrefixWith(name -> name + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(availabilityConfig)
                .withCacheConfiguration(AVAILABILITY_CACHE, availabilityConfig)
                .build();
    }
}
