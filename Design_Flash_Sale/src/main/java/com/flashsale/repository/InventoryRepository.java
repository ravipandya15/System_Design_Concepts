package com.flashsale.repository;

import com.flashsale.model.entity.Inventory;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Standard find — used by Optimistic Locking strategy.
     * The @Version on the entity handles conflict detection automatically.
     */
    Optional<Inventory> findByFlashSaleId(Long flashSaleId);

    /**
     * PESSIMISTIC LOCKING — SELECT ... FOR UPDATE
     * This locks the row at the database level. Other transactions
     * attempting to read this row will BLOCK until this transaction commits.
     *
     * Used by: InventoryServicePessimistic
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.flashSaleId = :flashSaleId")
    Optional<Inventory> findByFlashSaleIdWithPessimisticLock(@Param("flashSaleId") Long flashSaleId);

    /**
     * OPTIMISTIC LOCKING — Explicit version check in SQL.
     * This is used for manual optimistic locking when you need
     * direct control (vs relying on JPA @Version).
     *
     * Returns number of rows updated:
     *   1 = success (version matched)
     *   0 = conflict (version changed by another transaction)
     *
     * Used by: InventoryServiceOptimistic
     */
    @Modifying
    @Query("UPDATE Inventory i " +
           "SET i.availableQuantity = i.availableQuantity - :quantity, " +
           "    i.reservedQuantity = i.reservedQuantity + :quantity, " +
           "    i.version = i.version + 1 " +
           "WHERE i.flashSaleId = :flashSaleId " +
           "  AND i.version = :expectedVersion " +
           "  AND i.availableQuantity >= :quantity")
    int reserveStockOptimistic(
            @Param("flashSaleId") Long flashSaleId,
            @Param("quantity") int quantity,
            @Param("expectedVersion") int expectedVersion
    );

    /**
     * Confirm a reservation — move quantity from reserved to sold.
     */
    @Modifying
    @Query("UPDATE Inventory i " +
           "SET i.reservedQuantity = i.reservedQuantity - :quantity, " +
           "    i.soldQuantity = i.soldQuantity + :quantity, " +
           "    i.version = i.version + 1 " +
           "WHERE i.flashSaleId = :flashSaleId " +
           "  AND i.reservedQuantity >= :quantity")
    int confirmReservation(
            @Param("flashSaleId") Long flashSaleId,
            @Param("quantity") int quantity
    );

    /**
     * Release a reservation — move quantity back from reserved to available.
     * Used when payment fails or order is cancelled.
     */
    @Modifying
    @Query("UPDATE Inventory i " +
           "SET i.reservedQuantity = i.reservedQuantity - :quantity, " +
           "    i.availableQuantity = i.availableQuantity + :quantity, " +
           "    i.version = i.version + 1 " +
           "WHERE i.flashSaleId = :flashSaleId " +
           "  AND i.reservedQuantity >= :quantity")
    int releaseReservation(
            @Param("flashSaleId") Long flashSaleId,
            @Param("quantity") int quantity
    );
}
