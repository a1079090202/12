package com.example.dockyard.web;

import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.YardClock;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 预约接口：只负责收参数、取当前登录人、转调 AppointmentService。
 */
@Controller
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService appointments;
    private final CarrierRepository carriers;
    private final YardClock clock;
    private final int slotMinutes;

    public AppointmentController(AppointmentService appointments, CarrierRepository carriers, YardClock clock,
                                 @Value("${app.slot.length-minutes:30}") int slotMinutes) {
        this.appointments = appointments;
        this.carriers = carriers;
        this.clock = clock;
        this.slotMinutes = slotMinutes;
    }

    /** 按配置的时段时长（app.slot.length-minutes）生成 00:00 起的全天整点时段选项 */
    private List<String> slotTimes() {
        if (slotMinutes <= 0 || 1440 % slotMinutes != 0) {
            throw new IllegalStateException("app.slot.length-minutes 必须是能整除 1440 的正整数，当前=" + slotMinutes);
        }
        List<String> times = new ArrayList<>(1440 / slotMinutes);
        for (int minute = 0; minute < 1440; minute += slotMinutes) {
            times.add(LocalTime.ofSecondOfDay(minute * 60L).toString());
        }
        return times;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("dockTypes", DockType.values());
        model.addAttribute("slotTimes", slotTimes());
        model.addAttribute("today", clock.today());
        return "appointment/form";
    }

    @PostMapping
    public String book(@Valid BookingForm form, BindingResult br) {
        rejectIfInvalid(br);
        LoginUser me = CurrentUser.get();
        var req = toRequest(form);
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
        model.addAttribute("slotTimes", slotTimes());
        model.addAttribute("today", clock.today());
        return "appointment/override";
    }

    @PostMapping("/override")
    public String override(@Valid BookingForm form, BindingResult br) {
        rejectIfInvalid(br);
        LoginUser me = CurrentUser.get();
        if (form.getCarrierId() == null) {
            throw new BusinessRuleException("插单必须选择承运商");
        }
        if (form.getReason() == null || form.getReason().strip().isBlank()) {
            throw new BusinessRuleException("插单必须写明原因");
        }
        var req = toRequest(form);
        var appt = appointments.override(req, form.getCarrierId(), me.getId(), form.getReason().strip());
        return "redirect:/appointments/" + appt.getId() + "?overridden=1";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id) {
        appointments.cancel(id, CurrentUser.id());
        return "redirect:/appointments/" + id;
    }

    private AppointmentService.BookingRequest toRequest(BookingForm f) {
        return new AppointmentService.BookingRequest(
                f.getOrderNo(), f.getPlateNo(), f.getDriverName(), f.getDriverPhone(),
                f.getCargoType(), f.getDockType(), f.getSlotDate(), f.getSlotTime());
    }

    private void rejectIfInvalid(BindingResult br) {
        if (br.hasErrors()) {
            throw new BusinessRuleException(br.getFieldErrors().stream()
                    .findFirst()
                    .map(fe -> fe.getDefaultMessage())
                    .orElse("提交的参数不合法，请检查后重试"));
        }
    }
}
