package com.example.dockyard.service;

import com.example.dockyard.domain.CarrierDailyRate;
import com.example.dockyard.repo.CarrierDailyRateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 承运商当日费率维护。注意：
 *  - 费率按上海自然日生效，改价只影响之后核费的单，已核费单保存的是费率快照；
 *  - 核费区间内任何一天缺费率，FeeService 会直接拒绝核费，不存在静默兜底费率，
 *    因此调度员必须在车辆出场前把涉及日期的费率配齐（含可能跨日的次日）。
 */
@Service
public class RateService {

    private final CarrierDailyRateRepository rates;

    public RateService(CarrierDailyRateRepository rates) {
        this.rates = rates;
    }

    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public void upsertRate(Long carrierId, LocalDate date, BigDecimal ratePerHour, Long actorId) {
        if (ratePerHour == null || ratePerHour.signum() < 0) {
            throw new BusinessRuleException("费率不能为空或负数");
        }
        CarrierDailyRate rate = rates.findByCarrierIdAndRateDate(carrierId, date)
                .orElseGet(() -> {
                    CarrierDailyRate r = new CarrierDailyRate();
                    r.setCarrierId(carrierId);
                    r.setRateDate(date);
                    return r;
                });
        rate.setRatePerHour(ratePerHour);
        rate.setCreatedBy(actorId);
        rates.save(rate);
    }

    /** 某时间区间内（含首尾）已配置的日费率，供费率管理页展示缺口 */
    @Transactional(readOnly = true)
    public List<CarrierDailyRate> listBetween(LocalDate from, LocalDate to) {
        return rates.findByRateDateBetweenOrderByRateDateAsc(from, to);
    }
}
