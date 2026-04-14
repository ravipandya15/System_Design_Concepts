package com.flashsale.repository;

import com.flashsale.model.entity.InventoryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryLogRepository extends JpaRepository<InventoryLog, Long> {

    List<InventoryLog> findByFlashSaleIdOrderByCreatedAtDesc(Long flashSaleId);

    List<InventoryLog> findByOrderId(Long orderId);
}
