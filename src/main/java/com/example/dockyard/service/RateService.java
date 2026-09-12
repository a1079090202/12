package com.example.dockyard.service;

import com.example.dockyard.domain.CarrierDailyRate;
import com.example.dockyard.repo.CarrierDailyRateRepository;
import com.example.dockyard.repo.CarrierRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 承运商当日费率维护。改价只影响之后核费的单，已核费单保存的是费率快照。
 * 保存走数据库原子 upsert（on conflict），并发同日提交不产生重复行、不抛唯一约束异常。
 */
@Service
public class RateService {

    private final CarrierDailyRateRepository rates;
    private final CarrierRepository carriers;
    private final YardClock clock;

    public RateService(CarrierDailyRateRepository rates, CarrierRepository carriers, YardClock clock) {
        this.rates = rates;
        this.carriers = carriers;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public CarrierDailyRate upsertRate(Long carrierId, LocalDate date, BigDecimal ratePerHour, Long actorId) {
        if (carrierId == null || carriers.findById(carrierId).isEmpty()) {
            throw new BusinessRuleException("承运商不存在，无法配置费率");
        }
        if (ratePerHour == null || ratePerHour.signum() < 0) {
            throw new BusinessRuleException("费率不能为空或负数");
        }
        if (date == null) {
            throw new BusinessRuleException("必须选择费率生效日期");
        }
        rates.upsertRate(carrierId, date, ratePerHour, actorId, clock.now());
        return rates.findByCarrierIdAndRateDate(carrierId, date).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<CarrierDailyRate> listRecent() {
        return rates.findAllByOrderByRateDateDescCarrierIdAsc();
    }
}
