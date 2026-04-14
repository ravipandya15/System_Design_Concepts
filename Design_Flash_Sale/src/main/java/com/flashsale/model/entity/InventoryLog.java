package com.flashsale.model.entity;

import javax.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit log for every inventory mutation.
 * Provides a full trail for debugging, reconciliation, and compliance.
 */
@Entity
@Table(name = "inventory_logs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class InventoryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryOperation operation;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(name = "quantity_before", nullable = false)
    private Integer quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private Integer quantityAfter;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum InventoryOperation {
        RESERVE,    // Stock reserved for a purchase attempt
        CONFIRM,    // Reservation confirmed after payment
        RELEASE,    // Reservation released (payment failed / timeout)
        RECONCILE   // Background job corrected a mismatch
    }
}
