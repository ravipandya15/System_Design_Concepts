package com.flashsale.service;

import com.flashsale.model.dto.FlashSaleDTOs.*;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.entity.Inventory;
import com.flashsale.model.entity.Product;
import com.flashsale.repository.FlashSaleRepository;
import com.flashsale.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlashSaleService {

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY_PREFIX = "flash_sale:%d:stock";

    /**
     * Create a new flash sale + initialize inventory.
     */
    @Transactional
    public FlashSaleResponse createSale(CreateFlashSaleRequest request) {
        // Validate time range
        if (request.getEndTime().isBefore(request.getStartTime())) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }

        // Create flash sale entity
        FlashSale sale = FlashSale.builder()
                .productId(request.getProductId())
                .salePrice(request.getSalePrice())
                .totalQuantity(request.getTotalQuantity())
                .maxPerUser(request.getMaxPerUser())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .status(FlashSale.SaleStatus.UPCOMING)
                .build();

        sale = flashSaleRepository.save(sale);

        // Initialize inventory
        Inventory inventory = Inventory.builder()
                .flashSaleId(sale.getId())
                .availableQuantity(request.getTotalQuantity())
                .reservedQuantity(0)
                .soldQuantity(0)
                .build();

        inventoryRepository.save(inventory);

        log.info("Created flash sale: id={}, product={}, qty={}",
                sale.getId(), request.getProductId(), request.getTotalQuantity());

        return toResponse(sale, request.getTotalQuantity());
    }

    /**
     * Get sale details by ID.
     */
    @Transactional(readOnly = true)
    public FlashSaleResponse getSale(Long id) {
        FlashSale sale = flashSaleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Flash sale not found: " + id));

        int available = inventoryRepository.findByFlashSaleId(id)
                .map(Inventory::getAvailableQuantity)
                .orElse(0);

        return toResponse(sale, available);
    }

    /**
     * Get all currently active sales.
     */
    @Transactional(readOnly = true)
    public List<FlashSaleResponse> getActiveSales() {
        return flashSaleRepository.findActiveSales(LocalDateTime.now())
                .stream()
                .map(sale -> {
                    int available = inventoryRepository.findByFlashSaleId(sale.getId())
                            .map(Inventory::getAvailableQuantity)
                            .orElse(0);
                    return toResponse(sale, available);
                })
                .collect(Collectors.toList());
    }

    /**
     * Activate a sale — also pre-loads Redis cache (warm-up).
     */
    @Transactional
    public void activateSale(Long id) {
        FlashSale sale = flashSaleRepository.findById(id).orElseThrow();
        sale.setStatus(FlashSale.SaleStatus.ACTIVE);
        flashSaleRepository.save(sale);

        // Warm Redis cache with stock count
        Inventory inventory = inventoryRepository.findByFlashSaleId(id).orElseThrow();
        String stockKey = String.format(STOCK_KEY_PREFIX, id);
        redisTemplate.opsForValue().set(stockKey,
                String.valueOf(inventory.getAvailableQuantity()));

        log.info("Activated sale: id={}, stock loaded to Redis: {}", id,
                inventory.getAvailableQuantity());
    }

    /**
     * End a sale.
     */
    @Transactional
    public void endSale(Long id) {
        FlashSale sale = flashSaleRepository.findById(id).orElseThrow();
        sale.setStatus(FlashSale.SaleStatus.ENDED);
        flashSaleRepository.save(sale);

        // Clean up Redis
        String stockKey = String.format(STOCK_KEY_PREFIX, id);
        redisTemplate.delete(stockKey);

        log.info("Ended sale: id={}", id);
    }

    /**
     * Validate that a sale is currently active and within its time window.
     */
    public FlashSale validateSaleIsActive(Long saleId) {
        FlashSale sale = flashSaleRepository.findById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("SALE_NOT_FOUND"));

        if (!sale.isActive()) {
            throw new IllegalStateException("SALE_NOT_ACTIVE");
        }

        return sale;
    }

    // ───────────── Mapper ─────────────

    private FlashSaleResponse toResponse(FlashSale sale, int availableQuantity) {
        String discount = "0%";
        Product product = sale.getProduct();
        if (product != null && product.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal discountPct = product.getOriginalPrice()
                    .subtract(sale.getSalePrice())
                    .divide(product.getOriginalPrice(), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            discount = discountPct.intValue() + "%";
        }

        return FlashSaleResponse.builder()
                .id(sale.getId())
                .productId(sale.getProductId())
                .productName(product != null ? product.getName() : null)
                .originalPrice(product != null ? product.getOriginalPrice() : null)
                .salePrice(sale.getSalePrice())
                .discount(discount)
                .totalQuantity(sale.getTotalQuantity())
                .availableQuantity(availableQuantity)
                .maxPerUser(sale.getMaxPerUser())
                .startTime(sale.getStartTime())
                .endTime(sale.getEndTime())
                .status(sale.getStatus().name())
                .createdAt(sale.getCreatedAt())
                .build();
    }
}
