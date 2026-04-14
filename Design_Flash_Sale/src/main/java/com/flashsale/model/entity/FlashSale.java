package com.flashsale.model.entity;

import javax.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "flash_sales")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class FlashSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "max_per_user", nullable = false)
    @Builder.Default
    private Integer maxPerUser = 1;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SaleStatus status = SaleStatus.UPCOMING;

    /**
     * Version column for Optimistic Locking.
     * JPA automatically checks this on every UPDATE.
     * If another transaction modified this row, @Version mismatch
     * throws OptimisticLockException.
     */
    @Version
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ───────────── Business Logic ─────────────

    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return status == SaleStatus.ACTIVE
                && now.isAfter(startTime)
                && now.isBefore(endTime);
    }

    public boolean isUpcoming() {
        return status == SaleStatus.UPCOMING
                && LocalDateTime.now().isBefore(startTime);
    }

    public boolean hasEnded() {
        return status == SaleStatus.ENDED
                || LocalDateTime.now().isAfter(endTime);
    }

    public enum SaleStatus {
        UPCOMING, ACTIVE, ENDED, CANCELLED
    }
}
