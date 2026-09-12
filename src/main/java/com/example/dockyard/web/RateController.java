package com.example.dockyard.web;

import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.RateService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 调度员：维护各承运商按上海自然日生效的等候费率。 */
@Controller
@Validated
@RequestMapping("/rates")
public class RateController {

    private final RateService rates;
    private final CarrierRepository carriers;
    private final ReferenceData ref;
    private final YardClock clock;

    public RateController(RateService rates, CarrierRepository carriers,
                          ReferenceData ref, YardClock clock) {
        this.rates = rates;
        this.carriers = carriers;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("carriers", carriers.findAll());
        model.addAttribute("rateList", rates.listRecent());
        model.addAttribute("ref", ref);
        model.addAttribute("today", clock.today());
        return "rate/page";
    }

    @PostMapping
    public String save(@RequestParam @NotNull(message = "必须选择承运商") Long carrierId,
                       @RequestParam @NotNull(message = "必须选择日期")
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rateDate,
                       @RequestParam @NotNull(message = "费率不能为空")
                       @DecimalMin(value = "0.00", message = "费率不能为负") BigDecimal ratePerHour) {
        rates.upsertRate(carrierId, rateDate, ratePerHour, CurrentUser.id());
        return "redirect:/rates?saved=1";
    }
}
