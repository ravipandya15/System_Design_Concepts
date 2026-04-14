package com.flashsale.service;

import com.flashsale.model.dto.FlashSaleDTOs.*;
import com.flashsale.model.entity.*;
import com.flashsale.model.entity.Order.OrderStatus;
import com.flashsale.model.entity.Payment.PaymentStatus;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.PaymentRepository;
import com.flashsale.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Purchase Orchestrator — coordinates the entire purchase flow.
 *
 * This is the SAGA ORCHESTRATOR. It calls services in order and handles
 * compensating transactions (rollbacks) if any step fails:
 *
 *   1. Validate sale is active
 *   2. Check for duplicate purchase
 *   3. Reserve inventory (using whichever locking strategy is active)
 *   4. Create order
 *   5. Process payment
 *   6. Confirm order & inventory
 *
 * COMPENSATION ON FAILURE:
 *   If step 5 (payment) fails → release inventory + cancel order
 *   If step 4 (order) fails  → release inventory
 *   If step 3 (reserve) fails → return SOLD_OUT
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrchestrator {

    private final FlashSaleService flashSaleService;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    /**
     * Execute the complete purchase flow.
     *
     * @param flashSaleId    The flash sale to purchase from
     * @param userId         The authenticated user's ID
     * @param request        Purchase request with payment info
     * @param idempotencyKey Client-provided key for deduplication
     * @return PurchaseResponse on success
     * @throws IllegalStateException if sale is inactive, sold out, or already purchased
     */
    @Transactional
    public PurchaseResponse executePurchase(
            Long flashSaleId,
            Long userId,
            PurchaseRequest request,
            String idempotencyKey) {

        log.info("=== PURCHASE FLOW START === sale={}, user={}, key={}",
                flashSaleId, userId, idempotencyKey);

        // ─────────────────────────────────────────────
        // STEP 0: Idempotency Check
        // ─────────────────────────────────────────────
        var existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            log.info("Idempotent request detected: key={}, order={}",
                    idempotencyKey, existingOrder.get().getOrderNumber());
            return buildResponseFromExistingOrder(existingOrder.get());
        }

        // ─────────────────────────────────────────────
        // STEP 1: Validate sale is active
        // ─────────────────────────────────────────────
        FlashSale sale = flashSaleService.validateSaleIsActive(flashSaleId);
        log.info("Step 1 ✓ Sale is active: id={}", flashSaleId);

        // ─────────────────────────────────────────────
        // STEP 2: Check for duplicate purchase
        // ─────────────────────────────────────────────
        if (orderRepository.existsByUserIdAndFlashSaleId(userId, flashSaleId)) {
            throw new IllegalStateException("ALREADY_PURCHASED");
        }
        log.info("Step 2 ✓ No duplicate purchase found");

        // ─────────────────────────────────────────────
        // STEP 3: Reserve inventory
        //   (Uses whichever InventoryService impl is active:
        //    optimistic, pessimistic, or distributed lock)
        // ─────────────────────────────────────────────
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;

        // Validate against max per user
        if (quantity > sale.getMaxPerUser()) {
            throw new IllegalArgumentException(
                    "Quantity exceeds max per user. Max: " + sale.getMaxPerUser());
        }

        ReservationResult reservation = inventoryService.reserveStock(
                flashSaleId, userId, quantity);

        if (!reservation.isSuccess()) {
            log.warn("Step 3 ✗ Reservation failed: reason={}",
                    reservation.getFailureReason());
            throw new IllegalStateException(reservation.getFailureReason());
        }
        log.info("Step 3 ✓ Inventory reserved: remaining={}", reservation.getRemainingStock());

        // ─────────────────────────────────────────────
        // STEP 4: Create order
        // ─────────────────────────────────────────────
        Order order;
        try {
            order = createOrder(sale, userId, quantity, idempotencyKey);
            log.info("Step 4 ✓ Order created: number={}", order.getOrderNumber());
        } catch (Exception e) {
            // COMPENSATE: release inventory
            log.error("Step 4 ✗ Order creation failed, releasing inventory", e);
            inventoryService.releaseReservation(flashSaleId, null, quantity);
            throw e;
        }

        // ─────────────────────────────────────────────
        // STEP 5: Process payment
        // ─────────────────────────────────────────────
        Payment payment;
        try {
            payment = processPayment(order, request);
            log.info("Step 5 ✓ Payment processed: txn={}", payment.getTransactionId());
        } catch (Exception e) {
            // COMPENSATE: cancel order + release inventory
            log.error("Step 5 ✗ Payment failed, compensating", e);
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            inventoryService.releaseReservation(flashSaleId, order.getId(), quantity);
            throw new IllegalStateException("PAYMENT_FAILED");
        }

        // ─────────────────────────────────────────────
        // STEP 6: Confirm order & inventory
        // ─────────────────────────────────────────────
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        inventoryService.confirmReservation(flashSaleId, order.getId(), quantity);

        log.info("=== PURCHASE FLOW COMPLETE === order={}, sale={}, user={}",
                order.getOrderNumber(), flashSaleId, userId);

        // TODO: Publish OrderConfirmedEvent to Kafka for notifications

        return PurchaseResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .flashSaleId(flashSaleId)
                .quantity(quantity)
                .unitPrice(sale.getSalePrice())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .paymentId(payment.getId())
                .paymentStatus(payment.getStatus().name())
                .createdAt(order.getCreatedAt())
                .build();
    }

    // ───────────── Private Helpers ─────────────

    private Order createOrder(FlashSale sale, Long userId, int quantity, String idempotencyKey) {
        String orderNumber = String.format("FS-%s-%s",
                LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        BigDecimal totalAmount = sale.getSalePrice().multiply(BigDecimal.valueOf(quantity));

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .userId(userId)
                .flashSaleId(sale.getId())
                .quantity(quantity)
                .unitPrice(sale.getSalePrice())
                .totalAmount(totalAmount)
                .status(OrderStatus.PAYMENT_PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        return orderRepository.save(order);
    }

    private Payment processPayment(Order order, PurchaseRequest request) {
        // In production, this calls an external payment gateway (Stripe, Razorpay, etc.)
        // Here we simulate a synchronous payment.

        String transactionId = "txn_" + UUID.randomUUID().toString().substring(0, 12);

        Payment payment = Payment.builder()
                .orderId(order.getId())
                .transactionId(transactionId)
                .amount(order.getTotalAmount())
                .status(PaymentStatus.SUCCESS)
                .paymentMethod(request.getPaymentMethod())
                .gatewayResponse("{\"status\":\"approved\",\"code\":\"00\"}")
                .build();

        return paymentRepository.save(payment);
    }

    private PurchaseResponse buildResponseFromExistingOrder(Order order) {
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);

        return PurchaseResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .flashSaleId(order.getFlashSaleId())
                .quantity(order.getQuantity())
                .unitPrice(order.getUnitPrice())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .paymentId(payment != null ? payment.getId() : null)
                .paymentStatus(payment != null ? payment.getStatus().name() : null)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
