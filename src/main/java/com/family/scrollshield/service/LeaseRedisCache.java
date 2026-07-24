package com.family.scrollshield.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LeaseRedisCache {

    public static final String ACTIVE_KEY_PREFIX = "lease:active:";
    public static final String TOKEN_KEY_PREFIX = "lease:token:";

    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final Duration margin;

    public LeaseRedisCache(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        this.margin = Duration.ofSeconds(30);
    }

    private String activeKey(UUID memberId) {
        return ACTIVE_KEY_PREFIX + memberId;
    }

    private String tokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    public boolean tryAcquireFastPath(UUID memberId, String token, LeaseCacheEntry entry) {
        try {
            Instant now = Instant.now(clock);
            Duration ttl = entry.ttlFrom(now, margin);
            Boolean ok = redis.opsForValue().setIfAbsent(activeKey(memberId), token, ttl);
            if (Boolean.FALSE.equals(ok)) {
                return false;
            }
            redis.opsForValue().set(tokenKey(token), entry, ttl);
            return true;
        } catch (Exception e) {
            log.warn("Redis fast-path failed, fallback to PG: {}", e.getMessage());
            return true;
        }
    }

    public String currentActiveToken(UUID memberId) {
        try {
            Object v = redis.opsForValue().get(activeKey(memberId));
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("Redis lookup failed, fallback to PG: {}", e.getMessage());
            return null;
        }
    }

    public LeaseCacheEntry getByToken(String token) {
        try {
            Object v = redis.opsForValue().get(tokenKey(token));
            if (v == null) return null;
            if (v instanceof LeaseCacheEntry lce) return lce;
            return null;
        } catch (Exception e) {
            log.warn("Redis lookup by token failed: {}", e.getMessage());
            return null;
        }
    }

    public void refresh(String token, UUID memberId, Instant newExpiry, long revision) {
        try {
            Instant now = Instant.now(clock);
            Duration ttl = Duration.between(now, newExpiry).plus(margin);
            if (ttl.isNegative()) ttl = Duration.ofMillis(1);
            LeaseCacheEntry entry = new LeaseCacheEntry(
                    null, memberId, token, newExpiry, revision);
            redis.opsForValue().set(tokenKey(token), entry, ttl);
            redis.expire(activeKey(memberId), ttl);
        } catch (Exception e) {
            log.warn("Redis refresh failed: {}", e.getMessage());
        }
    }

    public void release(UUID memberId, String token) {
        try {
            redis.delete(activeKey(memberId));
            if (token != null) redis.delete(tokenKey(token));
        } catch (Exception e) {
            log.warn("Redis release failed: {}", e.getMessage());
        }
    }

    public void clearAll() {
        try {
            var keys = redis.keys(ACTIVE_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
            var tk = redis.keys(TOKEN_KEY_PREFIX + "*");
            if (tk != null && !tk.isEmpty()) redis.delete(tk);
        } catch (Exception e) {
            log.warn("Redis clearAll failed: {}", e.getMessage());
        }
    }
}
