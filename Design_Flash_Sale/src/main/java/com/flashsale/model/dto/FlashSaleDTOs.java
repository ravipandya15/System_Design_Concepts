package com.flashsale.model.dto;

import javax.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * All request and response DTOs for the Flash Sale API.
 */
public class FlashSaleDTOs {

    // ════════════════════════════════════════════
    //  FLASH SALE DTOs
    // ════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateFlashSaleRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull @DecimalMin(value = "0.01", message = "Sale price must be > 0")
        private BigDecimal salePrice;

        @NotNull @Min(value = 1, message = "Total quantity must be >= 1")
        private Integer totalQuantity;

        @Min(value = 1) @Builder.Default
        private Integer maxPerUser = 1;

        @NotNull @Future(message = "Start time must be in the future")
        private LocalDateTime startTime;

        @NotNull @Future(message = "End time must be in the future")
        private LocalDateTime endTime;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FlashSaleResponse {
        private Long id;
        private Long productId;
        private String productName;
        private BigDecimal originalPrice;
        private BigDecimal salePrice;
        private String discount;
        private Integer totalQuantity;
        private Integer availableQuantity;
        private Integer maxPerUser;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String status;
        private LocalDateTime createdAt;
    }

    // ════════════════════════════════════════════
    //  PURCHASE DTOs
    // ════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PurchaseRequest {
        @Min(value = 1, message = "Quantity must be >= 1")
        @Builder.Default
        private Integer quantity = 1;

        @NotBlank(message = "Payment method is required")
        private String paymentMethod;

        @NotBlank(message = "Payment token is required")
        private String paymentToken;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PurchaseResponse {
        private Long orderId;
        private String orderNumber;
        private Long flashSaleId;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalAmount;
        private String status;
        private Long paymentId;
        private String paymentStatus;
        private LocalDateTime createdAt;
    }

    // ════════════════════════════════════════════
    //  ORDER DTOs
    // ════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderResponse {
        private Long id;
        private String orderNumber;
        private Long flashSaleId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalAmount;
        private String status;
        private PaymentInfo payment;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PaymentInfo {
        private String transactionId;
        private String status;
        private String method;
    }

    // ════════════════════════════════════════════
    //  INVENTORY DTOs
    // ════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InventoryResponse {
        private Long flashSaleId;
        private Integer totalQuantity;
        private Integer availableQuantity;
        private Integer reservedQuantity;
        private Integer soldQuantity;
        private LocalDateTime lastUpdated;
    }

    // ════════════════════════════════════════════
    //  COMMON
    // ════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ErrorResponse {
        private String error;
        private String message;
        private LocalDateTime timestamp;
        private String path;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReservationResult {
        private boolean success;
        private Long flashSaleId;
        private Integer remainingStock;
        private String failureReason;
    }
}
