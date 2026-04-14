package com.flashsale.controller;

import com.flashsale.model.dto.FlashSaleDTOs.*;
import com.flashsale.service.FlashSaleService;
import com.flashsale.service.PurchaseOrchestrator;
import com.flashsale.service.inventory.InventoryService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Flash Sale REST API Controller.
 *
 * Endpoints:
 *   POST   /api/v1/flash-sales                    — Create a flash sale (admin)
 *   GET    /api/v1/flash-sales/{id}                — Get sale details
 *   GET    /api/v1/flash-sales/active              — List active sales
 *   POST   /api/v1/flash-sales/{id}/purchase       — Purchase an item (critical path)
 *   GET    /api/v1/flash-sales/{id}/inventory      — Get real-time stock
 *   PUT    /api/v1/flash-sales/{id}/activate       — Activate a sale (admin)
 *   PUT    /api/v1/flash-sales/{id}/end            — End a sale (admin)
 */
@RestController
@RequestMapping("/api/v1/flash-sales")
@RequiredArgsConstructor
@Slf4j
public class FlashSaleController {

    private final FlashSaleService flashSaleService;
    private final PurchaseOrchestrator purchaseOrchestrator;
    private final InventoryService inventoryService;

    // ════════════════════════════════════════════
    //  FLASH SALE CRUD
    // ════════════════════════════════════════════

    /**
     * POST /api/v1/flash-sales — Create a new flash sale.
     * Requires ADMIN role.
     */
    @PostMapping
    public ResponseEntity<FlashSaleResponse> createSale(
            @Valid @RequestBody CreateFlashSaleRequest request) {

        log.info("Creating flash sale: product={}, qty={}", request.getProductId(),
                request.getTotalQuantity());

        FlashSaleResponse response = flashSaleService.createSale(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/flash-sales/{id} — Get sale details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<FlashSaleResponse> getSale(@PathVariable Long id) {
        return ResponseEntity.ok(flashSaleService.getSale(id));
    }

    /**
     * GET /api/v1/flash-sales/active — Get all active sales.
     */
    @GetMapping("/active")
    public ResponseEntity<List<FlashSaleResponse>> getActiveSales() {
        return ResponseEntity.ok(flashSaleService.getActiveSales());
    }

    /**
     * PUT /api/v1/flash-sales/{id}/activate — Activate a sale (loads Redis cache).
     * Requires ADMIN role.
     */
    @PutMapping("/{id}/activate")
    public ResponseEntity<Void> activateSale(@PathVariable Long id) {
        flashSaleService.activateSale(id);
        return ResponseEntity.ok().build();
    }

    /**
     * PUT /api/v1/flash-sales/{id}/end — End a sale.
     * Requires ADMIN role.
     */
    @PutMapping("/{id}/end")
    public ResponseEntity<Void> endSale(@PathVariable Long id) {
        flashSaleService.endSale(id);
        return ResponseEntity.ok().build();
    }

    // ════════════════════════════════════════════
    //  PURCHASE — THE HOT PATH
    // ════════════════════════════════════════════

    /**
     * POST /api/v1/flash-sales/{id}/purchase — Purchase an item.
     *
     * Headers required:
     *   Authorization: Bearer {jwt}
     *   X-Idempotency-Key: {uuid}
     *
     * This is the most critical endpoint. Every call goes through:
     *   1. Rate limiting (handled by filter)
     *   2. Idempotency check
     *   3. Sale validation
     *   4. Inventory reservation (with configured locking strategy)
     *   5. Order creation
     *   6. Payment processing
     *   7. Order confirmation
     */
    @PostMapping("/{id}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {

        log.info("Purchase request: sale={}, user={}, idempotencyKey={}",
                id, userId, idempotencyKey);

        PurchaseResponse response = purchaseOrchestrator.executePurchase(
                id, userId, request, idempotencyKey);

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════
    //  INVENTORY
    // ════════════════════════════════════════════

    /**
     * GET /api/v1/flash-sales/{id}/inventory — Real-time stock check.
     */
    @GetMapping("/{id}/inventory")
    public ResponseEntity<InventoryResponse> getInventory(@PathVariable Long id) {
        var inventory = inventoryService.getAvailableStock(id);

        InventoryResponse response = InventoryResponse.builder()
                .flashSaleId(id)
                .availableQuantity(inventory)
                .build();

        return ResponseEntity.ok(response);
    }
}
