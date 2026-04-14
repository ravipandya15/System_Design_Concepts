package com.flashsale.model.entity;

import javax.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Inventory entity — the most contested row in the entire system.
 *
 * Concurrency control is critical here:
 * - @Version enables JPA Optimistic Locking (automatic version check on UPDATE)
 * - Pessimistic Locking uses @Lock(PESSIMISTIC_WRITE) on the repository query
 * - Distributed Locking wraps access with a Redis-based mutex
 */
@Entity
@Table(name = "inventory")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flash_sale_id", nullable = false, unique = true)
    private Long flashSaleId;

    @Column(name = "available_quantity", nullable = false)
    @Builder.Default
    private Integer availableQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Column(name = "sold_quantity", nullable = false)
    @Builder.Default
    private Integer soldQuantity = 0;

    /**
     * Version for Optimistic Locking.
     * Each UPDATE increments this. If two transactions read version=5,
     * only one can write version=6 — the other gets OptimisticLockException.
     */
    @Version
    @Column(nullable = false)
    private Integer version;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ───────────── Business Logic ─────────────

    /**
     * Check if enough stock is available for reservation.
     */
    public boolean canReserve(int quantity) {
        return availableQuantity >= quantity;
    }

    /**
     * Reserve stock: decrement available, increment reserved.
     * @throws IllegalStateException if not enough stock
     */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "Insufficient stock. Available: " + availableQuantity + ", requested: " + quantity
            );
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    /**
     * Confirm a reservation: move from reserved to sold.
     */
    public void confirmSale(int quantity) {
        if (reservedQuantity < quantity) {
            throw new IllegalStateException("Cannot confirm more than reserved quantity");
        }
        this.reservedQuantity -= quantity;
        this.soldQuantity += quantity;
    }

    /**
     * Release a reservation (e.g., payment failed): move back to available.
     */
    public void releaseReservation(int quantity) {
        if (reservedQuantity < quantity) {
            throw new IllegalStateException("Cannot release more than reserved quantity");
        }
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
    }
}
