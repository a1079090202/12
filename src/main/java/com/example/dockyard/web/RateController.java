package com.example.dockyard.web;

import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.RateService;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度员费率配置：按承运商 + 上海自然日维护当日费率（元/时）。
 * 出场核费时，计费区间覆盖的每一天都必须已配置费率，否则核费被拒绝。
 */
@Controller
@RequestMapping("/rates")
public class RateController {

    /** 费率管理页默认展示的天数（含今日） */
    private static final int WINDOW_DAYS = 14;

    private final RateService rates;
    private final CarrierRepository carriers;
    private final YardClock clock;

    public RateController(RateService rates, CarrierRepository carriers, YardClock clock) {
        this.rates = rates;
        this.carriers = carriers;
        this.clock = clock;
    }

    @GetMapping
    public String page(@RequestParam(required = false) LocalDate from, Model model) {
        LocalDate start = from != null ? from : clock.today();
        LocalDate end = start.plusDays(WINDOW_DAYS - 1L);
        var carrierList = carriers.findAll();
        var configured = rates.listBetween(start, end);

        // 日期 × 承运商矩阵：key = carrierId + "|" + ISO 日期
        Map<String, BigDecimal> matrix = new LinkedHashMap<>();
        for (var r : configured) {
            matrix.put(r.getCarrierId() + "|" + r.getRateDate(), r.getRatePerHour());
        }
        List<LocalDate> dates = new ArrayList<>(WINDOW_DAYS);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            dates.add(d);
        }

        model.addAttribute("carrierList", carrierList);
        model.addAttribute("dates", dates);
        model.addAttribute("matrix", matrix);
        model.addAttribute("start", start);
        model.addAttribute("end", end);
        model.addAttribute("today", clock.today());
        return "rates/page";
    }

    @PostMapping
    public String upsert(@RequestParam Long carrierId,
                         @RequestParam LocalDate date,
                         @RequestParam BigDecimal ratePerHour) {
        rates.upsertRate(carrierId, date, ratePerHour, CurrentUser.id());
        return "redirect:/rates?from=" + date + "&saved=1";
    }
}
