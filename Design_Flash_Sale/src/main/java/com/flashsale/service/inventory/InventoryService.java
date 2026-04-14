package com.flashsale.service.inventory;

import com.flashsale.model.dto.FlashSaleDTOs.ReservationResult;

/**
 * Inventory service interface.
 * Three implementations exist, each using a different concurrency control strategy:
 *
 *   1. InventoryServiceOptimistic   — @Version / CAS-style retry
 *   2. InventoryServicePessimistic  — SELECT FOR UPDATE (row-level DB lock)
 *   3. InventoryServiceDistributedLock — Redis Redlock (cross-instance mutex)
 *
 * The active strategy is selected via Spring profiles:
 *   --spring.profiles.active=optimistic-lock
 *   --spring.profiles.active=pessimistic-lock
 *   --spring.profiles.active=distributed-lock
 */
public interface InventoryService {

    /**
     * Attempt to reserve stock for a purchase.
     * This is the HOT PATH — called for every purchase attempt.
     *
     * @param flashSaleId the flash sale to reserve stock from
     * @param userId      the user making the purchase (for duplicate check)
     * @param quantity    number of items to reserve
     * @return ReservationResult indicating success or failure with reason
     */
    ReservationResult reserveStock(Long flashSaleId, Long userId, int quantity);

    /**
     * Confirm a successful reservation (after payment).
     * Moves quantity from "reserved" to "sold".
     */
    void confirmReservation(Long flashSaleId, Long orderId, int quantity);

    /**
     * Release a failed reservation (payment failed, timeout, cancel).
     * Moves quantity from "reserved" back to "available".
     */
    void releaseReservation(Long flashSaleId, Long orderId, int quantity);

    /**
     * Get current available stock count.
     */
    int getAvailableStock(Long flashSaleId);
}
