package com.example.dockyard.repo;

import com.example.dockyard.domain.CarrierDailyRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CarrierDailyRateRepository extends JpaRepository<CarrierDailyRate, Long> {

    Optional<CarrierDailyRate> findByCarrierIdAndRateDate(Long carrierId, LocalDate rateDate);

    List<CarrierDailyRate> findAllByOrderByRateDateDescCarrierIdAsc();

    List<CarrierDailyRate> findByCarrierIdOrderByRateDateDesc(Long carrierId);

    /**
     * 原子 upsert：依赖 unique(carrier_id, rate_date)，并发同日提交不会撞唯一约束/产生重复行。
     * 已存在则只改费率与最后操作人，保留原始 created_at。
     */
    @Modifying
    @Query(value = """
            insert into carrier_daily_rate (carrier_id, rate_date, rate_per_hour, created_by, created_at)
            values (:carrierId, :rateDate, :ratePerHour, :createdBy, :now)
            on conflict (carrier_id, rate_date)
            do update set rate_per_hour = excluded.rate_per_hour,
                          created_by     = excluded.created_by
            """, nativeQuery = true)
    int upsertRate(@Param("carrierId") Long carrierId,
                   @Param("rateDate") LocalDate rateDate,
                   @Param("ratePerHour") BigDecimal ratePerHour,
                   @Param("createdBy") Long createdBy,
                   @Param("now") Instant now);
}
