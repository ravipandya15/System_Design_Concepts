package com.flashsale.service.inventory;

import com.flashsale.model.dto.FlashSaleDTOs.ReservationResult;
import com.flashsale.model.entity.Inventory;
import com.flashsale.model.entity.InventoryLog;
import com.flashsale.model.entity.InventoryLog.InventoryOperation;
import com.flashsale.repository.InventoryLogRepository;
import com.flashsale.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  STRATEGY 1: OPTIMISTIC LOCKING
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * HOW IT WORKS:
 *   1. READ the inventory row (including its version number)
 *   2. CHECK if enough stock is available
 *   3. UPDATE the row with a WHERE clause that includes the version
 *   4. If version matches → 1 row updated → SUCCESS
 *   5. If version doesn't match → 0 rows updated → RETRY
 *
 * WHY "OPTIMISTIC"?
 *   We optimistically assume no one else will modify the row
 *   between our READ and UPDATE. If someone does, we detect it
 *   at write time and retry. No locks are held during the read.
 *
 * SQL EXECUTED:
 *   UPDATE inventory
 *   SET available_quantity = available_quantity - 1,
 *       reserved_quantity = reserved_quantity + 1,
 *       version = version + 1
 *   WHERE flash_sale_id = ?
 *     AND version = ?              ← conflict detection
 *     AND available_quantity >= ?   ← stock check
 *
 * BEST FOR: Medium contention, high throughput
 * RISK: Retry storms at very high contention
 */
@Service
@Profile("optimistic-lock")
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceOptimistic implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 10;

    @Override
    @Transactional
    public ReservationResult reserveStock(Long flashSaleId, Long userId, int quantity) {
        log.info("[OPTIMISTIC] Attempting to reserve {} units for sale={}, user={}",
                quantity, flashSaleId, userId);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            // STEP 1: Read current state (including version)
            Inventory inventory = inventoryRepository.findByFlashSaleId(flashSaleId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Inventory not found for flash sale: " + flashSaleId));

            int currentVersion = inventory.getVersion();
            int availableBefore = inventory.getAvailableQuantity();

            // STEP 2: Check stock availability
            if (availableBefore < quantity) {
                log.warn("[OPTIMISTIC] SOLD OUT: sale={}, available={}", flashSaleId, availableBefore);
                return ReservationResult.builder()
                        .success(false)
                        .flashSaleId(flashSaleId)
                        .remainingStock(availableBefore)
                        .failureReason("SOLD_OUT")
                        .build();
            }

            // STEP 3: Attempt UPDATE with version check
            // Returns 1 if version matched (success), 0 if version changed (conflict)
            int rowsUpdated = inventoryRepository.reserveStockOptimistic(
                    flashSaleId, quantity, currentVersion
            );

            if (rowsUpdated == 1) {
                // SUCCESS — version matched, row was updated
                log.info("[OPTIMISTIC] Reserved successfully: sale={}, attempt={}, remaining={}",
                        flashSaleId, attempt, availableBefore - quantity);

                // Log the mutation for audit trail
                inventoryLogRepository.save(InventoryLog.builder()
                        .flashSaleId(flashSaleId)
                        .operation(InventoryOperation.RESERVE)
                        .quantityChange(-quantity)
                        .quantityBefore(availableBefore)
                        .quantityAfter(availableBefore - quantity)
                        .build());

                return ReservationResult.builder()
                        .success(true)
                        .flashSaleId(flashSaleId)
                        .remainingStock(availableBefore - quantity)
                        .build();
            }

            // CONFLICT — another transaction modified the row between our READ and UPDATE
            log.warn("[OPTIMISTIC] Version conflict: sale={}, expectedVersion={}, attempt={}/{}",
                    flashSaleId, currentVersion, attempt, MAX_RETRIES);

            if (attempt < MAX_RETRIES) {
                try {
                    // Brief backoff before retry
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // All retries exhausted — could not acquire in time
        log.error("[OPTIMISTIC] All {} retries exhausted for sale={}", MAX_RETRIES, flashSaleId);
        return ReservationResult.builder()
                .success(false)
                .flashSaleId(flashSaleId)
                .failureReason("CONTENTION_TOO_HIGH")
                .build();
    }

    @Override
    @Transactional
    public void confirmReservation(Long flashSaleId, Long orderId, int quantity) {
        int rows = inventoryRepository.confirmReservation(flashSaleId, quantity);
        if (rows == 0) {
            throw new IllegalStateException("Failed to confirm reservation for sale: " + flashSaleId);
        }
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
        int rows = inventoryRepository.releaseReservation(flashSaleId, quantity);
        if (rows == 0) {
            throw new IllegalStateException("Failed to release reservation for sale: " + flashSaleId);
        }
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
    @Transactional(readOnly = true)
    public int getAvailableStock(Long flashSaleId) {
        return inventoryRepository.findByFlashSaleId(flashSaleId)
                .map(Inventory::getAvailableQuantity)
                .orElse(0);
    }
}
