package com.example.dockyard.web;

import com.example.dockyard.domain.DockType;
import com.example.dockyard.repo.CarrierRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.AppointmentService;
import com.example.dockyard.service.YardClock;
import com.example.dockyard.web.form.BookingForm;
import com.example.dockyard.web.form.OverrideForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 预约接口：只负责收参数、取当前登录人、转调 AppointmentService。
 * 表单字段全部经 Bean Validation 校验（长度/格式/日期），非法输入 400，不透传到数据库。
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
    public String book(@Valid @ModelAttribute BookingForm form, BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
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
        model.addAttribute("slotTimes", SLOT_TIMES);
        model.addAttribute("today", clock.today());
        return "appointment/override";
    }

    @PostMapping("/override")
    public String override(@Valid @ModelAttribute OverrideForm form, BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
        LoginUser me = CurrentUser.get();
        var appt = appointments.override(toRequest(form), form.getCarrierId(),
                me.getId(), form.getReason().strip());
        return "redirect:/appointments/" + appt.getId() + "?overridden=1";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id) {
        appointments.cancel(id, CurrentUser.id());
        return "redirect:/appointments/" + id;
    }

    private AppointmentService.BookingRequest toRequest(BookingForm f) {
        return new AppointmentService.BookingRequest(
                f.getOrderNo().strip(),
                f.getPlateNo().strip(),
                f.getDriverName().strip(),
                f.getDriverPhone() == null || f.getDriverPhone().isBlank() ? null : f.getDriverPhone().strip(),
                f.getCargoType().strip(),
                f.getDockType(),
                f.getSlotDate(),
                f.getSlotTime());
    }
}
