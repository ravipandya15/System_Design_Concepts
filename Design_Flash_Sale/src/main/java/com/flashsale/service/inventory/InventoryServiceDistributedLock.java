package com.flashsale.service.inventory;

import com.flashsale.model.dto.FlashSaleDTOs.ReservationResult;
import com.flashsale.model.entity.Inventory;
import com.flashsale.model.entity.InventoryLog;
import com.flashsale.model.entity.InventoryLog.InventoryOperation;
import com.flashsale.repository.InventoryLogRepository;
import com.flashsale.repository.InventoryRepository;
import com.flashsale.service.lock.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  STRATEGY 3: DISTRIBUTED LOCK (Redis Mutex)
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * HOW IT WORKS (Two-layer approach):
 *
 *   LAYER 1 — Redis Atomic DECR (fast rejection)
 *     - Before acquiring any lock, atomically decrement Redis counter
 *     - If counter goes below 0 → SOLD OUT → reject immediately
 *     - This handles 90%+ of traffic without touching the database
 *
 *   LAYER 2 — Redis Distributed Lock + DB Write (final consistency)
 *     - Acquire mutex: SET lock:flash_sale:{id} {uuid} NX EX 5
 *     - Inside the critical section: UPDATE the database
 *     - Release mutex: DEL lock:flash_sale:{id} (if value == uuid)
 *
 * WHY TWO LAYERS?
 *   Redis DECR is O(1) — can handle 100K+ ops/sec per node.
 *   If we only have 500 items, 499,500 out of 500,000 requests are
 *   rejected at Layer 1 without ever touching the DB or the lock.
 *   Only ~500 requests need the distributed lock.
 *
 * REDIS COMMANDS:
 *   Layer 1:
 *     DECR flash_sale:{id}:stock           → returns remaining count
 *     INCR flash_sale:{id}:stock           → rollback on failure
 *
 *   Layer 2:
 *     SET lock:flash_sale:{id} {uuid} NX EX 5  → acquire mutex
 *     ... (critical section: DB update) ...
 *     DEL lock:flash_sale:{id}                  → release mutex
 *
 * BEST FOR: Very high concurrency (100K+ concurrent users)
 * RISK: Redis as single point of failure (mitigate with Redis Cluster)
 */
@Service
@Profile("distributed-lock")
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceDistributedLock implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final RedisDistributedLock distributedLock;
    private final StringRedisTemplate redisTemplate;

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final String STOCK_KEY_PREFIX = "flash_sale:%d:stock";
    private static final String LOCK_KEY_PREFIX = "flash_sale:%d";

    @Override
    @Transactional
    public ReservationResult reserveStock(Long flashSaleId, Long userId, int quantity) {
        log.info("[DISTRIBUTED] Attempting reserve: sale={}, user={}, qty={}",
                flashSaleId, userId, quantity);

        // ═══════════════════════════════════════════════════
        //  LAYER 1: Redis Atomic DECR — Fast Rejection
        // ═══════════════════════════════════════════════════
        //
        // DECR is atomic in Redis — no two threads can read the
        // same value. This gives us lock-free stock checking.
        //
        // Example with 500 stock:
        //   Thread 1: DECR → 499 (proceed)
        //   Thread 2: DECR → 498 (proceed)
        //   ...
        //   Thread 500: DECR → 0 (proceed — last item)
        //   Thread 501: DECR → -1 (REJECTED — sold out)
        //   Thread 501: INCR → 0 (rollback the decrement)

        String stockKey = String.format(STOCK_KEY_PREFIX, flashSaleId);
        Long remaining = redisTemplate.opsForValue().decrement(stockKey);

        if (remaining == null || remaining < 0) {
            // SOLD OUT — rollback the decrement
            redisTemplate.opsForValue().increment(stockKey);
            log.warn("[DISTRIBUTED] Layer 1 rejection: SOLD OUT, sale={}", flashSaleId);

            return ReservationResult.builder()
                    .success(false)
                    .flashSaleId(flashSaleId)
                    .remainingStock(0)
                    .failureReason("SOLD_OUT")
                    .build();
        }

        // ═══════════════════════════════════════════════════
        //  LAYER 2: Distributed Lock + DB Write
        // ═══════════════════════════════════════════════════
        //
        // Only ~500 requests (= stock count) reach this point.
        // We acquire a Redis mutex to serialize DB writes.

        String lockKey = String.format(LOCK_KEY_PREFIX, flashSaleId);
        String lockToken = distributedLock.acquire(lockKey, LOCK_TTL);

        if (lockToken == null) {
            // Could not acquire lock — rollback Redis counter
            redisTemplate.opsForValue().increment(stockKey);
            log.error("[DISTRIBUTED] Failed to acquire lock: sale={}", flashSaleId);

            return ReservationResult.builder()
                    .success(false)
                    .flashSaleId(flashSaleId)
                    .failureReason("SERVICE_BUSY")
                    .build();
        }

        try {
            // ┌────────────────────────────────────────┐
            // │  CRITICAL SECTION (mutex held)         │
            // │  Only ONE thread executes this block   │
            // │  across ALL application instances.     │
            // └────────────────────────────────────────┘

            Inventory inventory = inventoryRepository.findByFlashSaleId(flashSaleId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Inventory not found: " + flashSaleId));

            int availableBefore = inventory.getAvailableQuantity();

            // Double-check against DB (Redis might be slightly out of sync)
            if (!inventory.canReserve(quantity)) {
                // DB says sold out — correct Redis counter
                redisTemplate.opsForValue().increment(stockKey);
                log.warn("[DISTRIBUTED] DB-level sold out (Redis/DB mismatch): sale={}",
                        flashSaleId);

                return ReservationResult.builder()
                        .success(false)
                        .flashSaleId(flashSaleId)
                        .remainingStock(0)
                        .failureReason("SOLD_OUT")
                        .build();
            }

            // Update database
            inventory.reserve(quantity);
            inventoryRepository.save(inventory);

            // Audit log
            inventoryLogRepository.save(InventoryLog.builder()
                    .flashSaleId(flashSaleId)
                    .operation(InventoryOperation.RESERVE)
                    .quantityChange(-quantity)
                    .quantityBefore(availableBefore)
                    .quantityAfter(inventory.getAvailableQuantity())
                    .build());

            log.info("[DISTRIBUTED] Reserved: sale={}, remaining={}", flashSaleId,
                    inventory.getAvailableQuantity());

            return ReservationResult.builder()
                    .success(true)
                    .flashSaleId(flashSaleId)
                    .remainingStock(inventory.getAvailableQuantity())
                    .build();

        } catch (Exception e) {
            // On any failure, rollback the Redis counter
            redisTemplate.opsForValue().increment(stockKey);
            log.error("[DISTRIBUTED] Error during reservation: sale={}", flashSaleId, e);
            throw e;

        } finally {
            // ┌────────────────────────────────────────┐
            // │ ALWAYS release the lock, even on error │
            // └────────────────────────────────────────┘
            distributedLock.release(lockKey, lockToken);
        }
    }

    @Override
    @Transactional
    public void confirmReservation(Long flashSaleId, Long orderId, int quantity) {
        inventoryRepository.confirmReservation(flashSaleId, quantity);
        inventoryLogRepository.save(InventoryLog.builder()
                .flashSaleId(flashSaleId)
                .orderId(orderId)
                .operation(InventoryOperation.CONFIRM)
                .quantityChange(-quantity)
                .quantityBefore(quantity)
                .quantityAfter(0)
                .build());
    }

    @Override
    @Transactional
    public void releaseReservation(Long flashSaleId, Long orderId, int quantity) {
        // Release from DB
        inventoryRepository.releaseReservation(flashSaleId, quantity);

        // Also increment Redis counter so the stock becomes available again
        String stockKey = String.format(STOCK_KEY_PREFIX, flashSaleId);
        redisTemplate.opsForValue().increment(stockKey, quantity);

        inventoryLogRepository.save(InventoryLog.builder()
                .flashSaleId(flashSaleId)
                .orderId(orderId)
                .operation(InventoryOperation.RELEASE)
                .quantityChange(quantity)
                .quantityBefore(0)
                .quantityAfter(quantity)
                .build());
    }

    @Override
    public int getAvailableStock(Long flashSaleId) {
        // Fast path: read from Redis
        String stockKey = String.format(STOCK_KEY_PREFIX, flashSaleId);
        String value = redisTemplate.opsForValue().get(stockKey);

        if (value != null) {
            return Integer.parseInt(value);
        }

        // Fallback: read from DB
        return inventoryRepository.findByFlashSaleId(flashSaleId)
                .map(Inventory::getAvailableQuantity)
                .orElse(0);
    }
}
