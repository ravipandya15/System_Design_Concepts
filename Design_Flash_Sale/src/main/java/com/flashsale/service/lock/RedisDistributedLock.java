package com.flashsale.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Redis-based Distributed Lock (simplified Redlock).
 *
 * ALGORITHM:
 *   Acquire: SET lock:{key} {uuid} NX EX {ttl}
 *     - NX = set only if key does NOT exist (atomic)
 *     - EX = auto-expire after ttl seconds (prevents deadlocks)
 *     - uuid = unique token to ensure only the owner can release
 *
 *   Release: DEL lock:{key} only if value == uuid (via Lua script)
 *     - Lua script runs atomically on Redis server
 *     - Prevents releasing someone else's lock
 *
 * USAGE:
 *   String token = lock.acquire("flash_sale:1", Duration.ofSeconds(5));
 *   if (token != null) {
 *       try {
 *           // critical section
 *       } finally {
 *           lock.release("flash_sale:1", token);
 *       }
 *   }
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisDistributedLock {

    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_PREFIX = "lock:";
    private static final int DEFAULT_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 50;

    /**
     * Lua script for safe lock release.
     * Atomically checks that the lock value matches our token before deleting.
     * Without this, a slow thread could accidentally release another thread's lock.
     *
     * RACE CONDITION WITHOUT LUA:
     *   Thread A: GET lock:x → "token-A" (valid)
     *   Thread A: (GC pause or slow execution)
     *   Redis:    lock:x expires automatically
     *   Thread B: SET lock:x "token-B" NX → OK (acquires lock)
     *   Thread A: DEL lock:x ← DELETES THREAD B's LOCK!
     *
     * WITH LUA (atomic):
     *   if redis.call('get', KEYS[1]) == ARGV[1] then
     *       return redis.call('del', KEYS[1])   -- our lock, safe to delete
     *   else
     *       return 0   -- not our lock, do nothing
     *   end
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    /**
     * Try to acquire a distributed lock with retries.
     *
     * @param key  Lock identifier (e.g., "flash_sale:42")
     * @param ttl  Lock expiry time (prevents deadlocks if holder crashes)
     * @return Unique token if acquired (needed for release), null if failed
     */
    public String acquire(String key, Duration ttl) {
        String lockKey = LOCK_PREFIX + key;
        String token = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < DEFAULT_RETRY_COUNT; attempt++) {
            // SET lockKey token NX EX ttlSeconds
            // NX: only set if not exists → atomic mutex
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, ttl);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Lock acquired: key={}, token={}, attempt={}", key, token, attempt);
                return token;
            }

            // Lock held by another thread — wait and retry
            log.debug("Lock busy: key={}, attempt={}/{}", key, attempt + 1, DEFAULT_RETRY_COUNT);
            try {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1)); // exponential-ish backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        log.warn("Failed to acquire lock after {} retries: key={}", DEFAULT_RETRY_COUNT, key);
        return null; // all retries exhausted
    }

    /**
     * Release a distributed lock.
     * Only succeeds if the lock is still held by the caller (token must match).
     *
     * @param key   Lock identifier
     * @param token Token returned by acquire()
     * @return true if lock was released, false if it expired or was stolen
     */
    public boolean release(String key, String token) {
        String lockKey = LOCK_PREFIX + key;

        // Execute Lua script atomically:
        // Check that lock value matches our token, then delete
        Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                token
        );

        boolean released = result != null && result == 1L;
        if (released) {
            log.debug("Lock released: key={}", key);
        } else {
            log.warn("Lock release failed (expired or stolen): key={}", key);
        }
        return released;
    }

    /**
     * Extend the TTL of an existing lock (for long-running operations).
     * Only extends if the lock is still held by the caller.
     */
    public boolean extend(String key, String token, Duration newTtl) {
        String lockKey = LOCK_PREFIX + key;
        String currentValue = redisTemplate.opsForValue().get(lockKey);

        if (token.equals(currentValue)) {
            return Boolean.TRUE.equals(redisTemplate.expire(lockKey, newTtl));
        }
        return false;
    }
}
