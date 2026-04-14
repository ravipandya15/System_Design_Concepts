package com.flashsale.repository;

import com.flashsale.model.entity.FlashSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FlashSaleRepository extends JpaRepository<FlashSale, Long> {

    List<FlashSale> findByStatus(FlashSale.SaleStatus status);

    @Query("SELECT f FROM FlashSale f WHERE f.status = 'ACTIVE' AND f.startTime <= :now AND f.endTime > :now")
    List<FlashSale> findActiveSales(LocalDateTime now);

    @Query("SELECT f FROM FlashSale f WHERE f.status = 'UPCOMING' AND f.startTime <= :now")
    List<FlashSale> findSalesReadyToActivate(LocalDateTime now);

    @Query("SELECT f FROM FlashSale f WHERE f.status = 'ACTIVE' AND f.endTime <= :now")
    List<FlashSale> findSalesReadyToEnd(LocalDateTime now);
}
