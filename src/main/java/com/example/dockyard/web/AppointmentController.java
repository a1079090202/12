package com.example.dockyard.web;

import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 预约接口：只负责收参数、取当前登录人、转调 AppointmentService。
 */
@Controller
@RequestMapping("/appointments")
public class AppointmentController {

    /** 30 分钟整点时段选项 00:00–23:30 */
    static final List<String> SLOT_TIMES = buildSlotTimes();

    private static List<String> buildSlotTimes() {
        List<String> times = new ArrayList<>(48);
        for (int h = 0; h < 24; h++) {
            times.add(String.format("%02d:00", h));
            times.add(String.format("%02d:30", h));
        }
        return times;
    }

    private final AppointmentService appointments;
    private final CarrierRepository carriers;
    private final YardClock clock;

    public AppointmentController(AppointmentService appointments, CarrierRepository carriers, YardClock clock) {
        this.appointments = appointments;
        this.carriers = carriers;
        this.clock = clock;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("dockTypes", DockType.values());
        model.addAttribute("slotTimes", SLOT_TIMES);
        model.addAttribute("today", clock.today());
        return "appointment/form";
    }

    @PostMapping
    public String book(@RequestParam String orderNo,
                       @RequestParam String plateNo,
                       @RequestParam String driverName,
                       @RequestParam(required = false) String driverPhone,
                       @RequestParam String cargoType,
                       @RequestParam DockType dockType,
                       @RequestParam LocalDate slotDate,
                       @RequestParam String slotTime) {
        LoginUser me = CurrentUser.get();
        var req = new AppointmentService.BookingRequest(
                orderNo, plateNo, driverName, driverPhone, cargoType, dockType, slotDate, slotTime);
        var appt = appointments.book(req, me.getCarrierId(), me.getId());
        return "redirect:/appointments/" + appt.getId() + "?booked=1";
    }

    @GetMapping("/mine")
    public String mine(Model model) {
        LoginUser me = CurrentUser.get();
        model.addAttribute("list", appointments.listByCarrier(me.getCarrierId()));
        model.addAttribute("clock", clock);
        return "appointment/mine";
    }

    @GetMapping("/override")
    public String overrideForm(Model model) {
        model.addAttribute("dockTypes", DockType.values());
        model.addAttribute("carriers", carriers.findAll());
        model.addAttribute("slotTimes", SLOT_TIMES);
        model.addAttribute("today", clock.today());
        return "appointment/override";
    }

    @PostMapping("/override")
    public String override(@RequestParam Long carrierId,
                           @RequestParam String orderNo,
                           @RequestParam String plateNo,
                           @RequestParam String driverName,
                           @RequestParam(required = false) String driverPhone,
                           @RequestParam String cargoType,
                           @RequestParam DockType dockType,
                           @RequestParam LocalDate slotDate,
                           @RequestParam String slotTime,
                           @RequestParam String reason) {
        LoginUser me = CurrentUser.get();
        if (carrierId == null) {
            throw new BusinessRuleException("插单必须选择承运商");
        }
        var req = new AppointmentService.BookingRequest(
                orderNo, plateNo, driverName, driverPhone, cargoType, dockType, slotDate, slotTime);
        var appt = appointments.override(req, carrierId, me.getId(), reason);
        return "redirect:/appointments/" + appt.getId() + "?overridden=1";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id) {
        appointments.cancel(id, CurrentUser.id());
        return "redirect:/appointments/" + id;
    }
}
