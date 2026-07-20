package com.stampedeio.catalog.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.stampedeio.catalog.api.dto.SeatResponse;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cache-aside read path for show availability (STAM-29).
 *
 * <p>A cache hit never touches Postgres (AC1). A cache miss is guarded by a
 * Redis {@code SETNX} single-flight lock so that a burst of concurrent
 * misses for the same show results in exactly one DB query, with every
 * caller receiving the winner's result (AC3). The Redis entry itself
 * carries a TTL safety net so a missed/duplicate invalidation event can't
 * pin stale data indefinitely (AC5).
 */
@Service
public class SeatAvailabilityCacheService {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityCacheService.class);

    private static final String LOCK_KEY_PREFIX = "lock:availability:";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    private final Duration lockTtl;
    private final Duration lockWaitTimeout;
    private final Duration lockPollInterval;

    public SeatAvailabilityCacheService(
            SeatRepository seatRepository,
            ShowRepository showRepository,
            CacheManager cacheManager,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${catalog.cache.lock-ttl-seconds:5}") long lockTtlSeconds,
            @Value("${catalog.cache.lock-wait-timeout-ms:3000}") long lockWaitTimeoutMs,
            @Value("${catalog.cache.lock-poll-interval-ms:20}") long lockPollIntervalMs) {
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        this.lockWaitTimeout = Duration.ofMillis(lockWaitTimeoutMs);
        this.lockPollInterval = Duration.ofMillis(lockPollIntervalMs);

        this.cacheHits = Counter.builder("cache_hits")
                .tag("cacheName", CacheConfig.AVAILABILITY_CACHE)
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("cache_misses")
                .tag("cacheName", CacheConfig.AVAILABILITY_CACHE)
                .register(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    public List<SeatResponse> getSeats(UUID showId) {
        Cache cache = availabilityCache();
        String cacheKey = cacheKey(showId);

        Cache.ValueWrapper wrapper = cache.get(cacheKey);
        if (wrapper != null) {
            cacheHits.increment();
            return (List<SeatResponse>) wrapper.get();
        }

        return loadWithSingleFlight(showId, cache, cacheKey);
    }

    public void evict(UUID showId) {
        availabilityCache().evict(cacheKey(showId));
    }

    @SuppressWarnings("unchecked")
    private List<SeatResponse> loadWithSingleFlight(UUID showId, Cache cache, String cacheKey) {
        String lockKey = LOCK_KEY_PREFIX + showId;
        String lockToken = UUID.randomUUID().toString();

        boolean acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, lockTtl));

        if (acquired) {
            try {
                // Double-check: another instance may have populated the cache
                // between our first miss and winning the lock.
                Cache.ValueWrapper wrapper = cache.get(cacheKey);
                if (wrapper != null) {
                    cacheHits.increment();
                    return (List<SeatResponse>) wrapper.get();
                }

                cacheMisses.increment();
                List<SeatResponse> fresh = loadFromDb(showId);
                cache.put(cacheKey, fresh);
                return fresh;
            } finally {
                unlock(lockKey, lockToken);
            }
        }

        return waitForWinnerOrFallback(showId, cache, cacheKey);
    }

    @SuppressWarnings("unchecked")
    private List<SeatResponse> waitForWinnerOrFallback(UUID showId, Cache cache, String cacheKey) {
        long deadline = System.nanoTime() + lockWaitTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                cacheHits.increment();
                return (List<SeatResponse>) wrapper.get();
            }
            try {
                Thread.sleep(lockPollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // The lock holder stalled or crashed before populating the cache;
        // fall back to a direct read rather than blocking the caller forever.
        log.warn("Timed out waiting for single-flight lock winner for show {}, querying DB directly", showId);
        cacheMisses.increment();
        return loadFromDb(showId);
    }

    private List<SeatResponse> loadFromDb(UUID showId) {
        if (!showRepository.existsById(showId)) {
            throw new ResourceNotFoundException("Show", showId);
        }
        return seatRepository.findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId).stream()
                .map(SeatResponse::from)
                .toList();
    }

    private void unlock(String lockKey, String lockToken) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    private Cache availabilityCache() {
        Cache cache = cacheManager.getCache(CacheConfig.AVAILABILITY_CACHE);
        if (cache == null) {
            throw new IllegalStateException("Cache '" + CacheConfig.AVAILABILITY_CACHE + "' is not configured");
        }
        return cache;
    }

    private String cacheKey(UUID showId) {
        return showId.toString();
    }
}
