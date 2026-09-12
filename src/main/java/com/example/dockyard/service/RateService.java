package com.example.dockyard.service;

import com.example.dockyard.domain.CarrierDailyRate;
import com.example.dockyard.repo.CarrierDailyRateRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 承运商当日费率维护。注意：改价只影响之后核费的单，已核费单保存的是费率快照。 */
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
}
