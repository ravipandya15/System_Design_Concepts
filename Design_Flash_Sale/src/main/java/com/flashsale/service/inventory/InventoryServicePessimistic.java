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
 *  STRATEGY 2: PESSIMISTIC LOCKING
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * HOW IT WORKS:
 *   1. BEGIN TRANSACTION
 *   2. SELECT * FROM inventory WHERE flash_sale_id = ? FOR UPDATE
 *      → This LOCKS the row at the DB level
 *      → All other transactions that try to read this row BLOCK
 *   3. Check available stock
 *   4. UPDATE the row (decrement available, increment reserved)
 *   5. COMMIT → releases the lock → next waiting transaction proceeds
 *
 * WHY "PESSIMISTIC"?
 *   We pessimistically assume that conflicts WILL happen, so we
 *   lock the row BEFORE reading it. No other transaction can even
 *   read the row while we hold the lock.
 *
 * SQL EXECUTED:
 *   SELECT * FROM inventory WHERE flash_sale_id = ? FOR UPDATE;
 *   -- ^^^ row is now LOCKED, other SELECTs FOR UPDATE will WAIT
 *
 *   UPDATE inventory
 *   SET available_quantity = available_quantity - 1,
 *       reserved_quantity = reserved_quantity + 1
 *   WHERE flash_sale_id = ?;
 *
 *   COMMIT;
 *   -- ^^^ lock is released
 *
 * DEADLOCK PREVENTION:
 *   - Always lock in the same order (by flash_sale_id)
 *   - Keep transactions SHORT (milliseconds, not seconds)
 *   - Set lock_timeout in PostgreSQL: SET lock_timeout = '5s'
 *
 * BEST FOR: High contention, short transactions, guaranteed ordering
 * RISK: DB connection exhaustion, deadlocks (mitigated by single row lock)
 */
@Service
@Profile("pessimistic-lock")
@RequiredArgsConstructor
@Slf4j
public class InventoryServicePessimistic implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;

    @Override
    @Transactional  // CRITICAL: Must be transactional. Lock is held until COMMIT/ROLLBACK
    public ReservationResult reserveStock(Long flashSaleId, Long userId, int quantity) {
        log.info("[PESSIMISTIC] Acquiring row lock for sale={}, user={}", flashSaleId, userId);

        // ┌──────────────────────────────────────────────────────┐
        // │ SELECT * FROM inventory                              │
        // │ WHERE flash_sale_id = ?                              │
        // │ FOR UPDATE;                                          │
        // │                                                      │
        // │ At this point, the row is EXCLUSIVELY LOCKED.        │
        // │ Any other transaction calling this method will BLOCK  │
        // │ here until we COMMIT or ROLLBACK.                    │
        // └──────────────────────────────────────────────────────┘
        Inventory inventory = inventoryRepository
                .findByFlashSaleIdWithPessimisticLock(flashSaleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory not found for flash sale: " + flashSaleId));

        int availableBefore = inventory.getAvailableQuantity();

        // Check stock while holding the lock — guaranteed accurate
        if (!inventory.canReserve(quantity)) {
            log.warn("[PESSIMISTIC] SOLD OUT: sale={}, available={}", flashSaleId, availableBefore);
            // Transaction commits immediately, releasing the lock
            return ReservationResult.builder()
                    .success(false)
                    .flashSaleId(flashSaleId)
                    .remainingStock(availableBefore)
                    .failureReason("SOLD_OUT")
                    .build();
        }

        // ┌──────────────────────────────────────────────────────┐
        // │ Modify the entity — JPA will generate:               │
        // │ UPDATE inventory SET available_quantity = ?,          │
        // │   reserved_quantity = ? WHERE id = ?                  │
        // │                                                      │
        // │ This is SAFE because we hold the exclusive lock.     │
        // │ No race condition is possible.                       │
        // └──────────────────────────────────────────────────────┘
        inventory.reserve(quantity);
        inventoryRepository.save(inventory);

        log.info("[PESSIMISTIC] Reserved: sale={}, remaining={}", flashSaleId,
                inventory.getAvailableQuantity());

        // Audit log
        inventoryLogRepository.save(InventoryLog.builder()
                .flashSaleId(flashSaleId)
                .operation(InventoryOperation.RESERVE)
                .quantityChange(-quantity)
                .quantityBefore(availableBefore)
                .quantityAfter(inventory.getAvailableQuantity())
                .build());

        // ┌──────────────────────────────────────────────────────┐
        // │ On method return, @Transactional triggers COMMIT.    │
        // │ The row lock is released, and the next blocked       │
        // │ transaction proceeds.                                │
        // └──────────────────────────────────────────────────────┘
        return ReservationResult.builder()
                .success(true)
                .flashSaleId(flashSaleId)
                .remainingStock(inventory.getAvailableQuantity())
                .build();
    }

    @Override
    @Transactional
    public void confirmReservation(Long flashSaleId, Long orderId, int quantity) {
        Inventory inventory = inventoryRepository
                .findByFlashSaleIdWithPessimisticLock(flashSaleId)
                .orElseThrow();

        int reservedBefore = inventory.getReservedQuantity();
        inventory.confirmSale(quantity);
        inventoryRepository.save(inventory);

        inventoryLogRepository.save(InventoryLog.builder()
                .flashSaleId(flashSaleId)
                .orderId(orderId)
                .operation(InventoryOperation.CONFIRM)
                .quantityChange(-quantity)
                .quantityBefore(reservedBefore)
                .quantityAfter(inventory.getReservedQuantity())
                .build());
    }

    @Override
    @Transactional
    public void releaseReservation(Long flashSaleId, Long orderId, int quantity) {
        Inventory inventory = inventoryRepository
                .findByFlashSaleIdWithPessimisticLock(flashSaleId)
                .orElseThrow();

        int reservedBefore = inventory.getReservedQuantity();
        inventory.releaseReservation(quantity);
        inventoryRepository.save(inventory);

        inventoryLogRepository.save(InventoryLog.builder()
                .flashSaleId(flashSaleId)
                .orderId(orderId)
                .operation(InventoryOperation.RELEASE)
                .quantityChange(quantity)
                .quantityBefore(reservedBefore)
                .quantityAfter(inventory.getReservedQuantity())
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public int getAvailableStock(Long flashSaleId) {
        // Regular read — no lock needed for stock check
        return inventoryRepository.findByFlashSaleId(flashSaleId)
                .map(Inventory::getAvailableQuantity)
                .orElse(0);
    }
}
