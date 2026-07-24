package com.family.scrollshield.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.family.scrollshield.domain.SessionLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Best-effort Redis accelerator for active leases. Every method swallows Redis
 * failures (unavailability, timeouts, deserialization errors) and degrades to a
 * cache miss, because Redis is explicitly a lossable layer: the authoritative
 * state lives in PostgreSQL. Losing Redis must never change correctness, only
 * latency.
 */
@Component
public class LeaseRedisCache {

    private static final Logger log = LoggerFactory.getLogger(LeaseRedisCache.class);

    private static final String ACTIVE_LEASE_KEY_PREFIX = "lease:active:member:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LeaseRedisCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private String key(UUID memberId) {
        return ACTIVE_LEASE_KEY_PREFIX + memberId;
    }

    /** Cache (or refresh) the active lease projection for a member. Never throws. */
    public void putActive(SessionLease lease) {
        try {
            LeaseCacheEntry entry = LeaseCacheEntry.from(lease);
            redis.opsForValue().set(key(lease.getMemberId()),
                    objectMapper.writeValueAsString(entry), TTL);
        } catch (Exception ex) {
            log.warn("Redis putActive failed (ignored, DB remains authoritative): {}", ex.toString());
        }
    }

    /** Look up the cached active lease for a member. Returns empty on any failure. */
    public Optional<LeaseCacheEntry> getActive(UUID memberId) {
        try {
            String raw = redis.opsForValue().get(key(memberId));
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, LeaseCacheEntry.class));
        } catch (Exception ex) {
            log.warn("Redis getActive failed (treated as miss): {}", ex.toString());
            return Optional.empty();
        }
    }

    /** Evict the cached active lease for a member. Never throws. */
    public void evict(UUID memberId) {
        try {
            redis.delete(key(memberId));
        } catch (Exception ex) {
            log.warn("Redis evict failed (ignored): {}", ex.toString());
        }
    }
}
