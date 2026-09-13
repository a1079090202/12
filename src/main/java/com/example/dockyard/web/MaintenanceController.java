package com.example.dockyard.web;

import com.example.dockyard.domain.Appointment;
import com.example.dockyard.domain.AppointmentReschedule;
import com.example.dockyard.domain.Dock;
import com.example.dockyard.domain.DockMaintenance;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.AppUserRepository;
import com.example.dockyard.repo.DockRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.DockMaintenanceService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import com.example.dockyard.web.form.MaintenanceForm;
import com.example.dockyard.web.form.RescheduleForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 月台保养停用（仅调度员）：登记/取消停用窗，查看受影响预约清单，逐条改期或标记待定。
 * URL 规则 + 服务方法 @PreAuthorize 双重强制，直接访问 /maintenance 同样被拦。
 */
@Controller
@RequestMapping("/maintenance")
public class MaintenanceController {

    private final DockMaintenanceService maintenance;
    private final DockRepository docks;
    private final AppointmentRepository appointments;
    private final AppUserRepository users;
    private final ReferenceData ref;
    private final YardClock clock;

    /** 30 分钟整点时段选项 00:00–23:30（与预约槽位粒度一致） */
    private static final List<String> SLOT_TIMES;
    static {
        List<String> times = new ArrayList<>(48);
        for (int h = 0; h < 24; h++) {
            times.add(String.format("%02d:00", h));
            times.add(String.format("%02d:30", h));
        }
        SLOT_TIMES = times;
    }

    public MaintenanceController(DockMaintenanceService maintenance, DockRepository docks,
                                 AppointmentRepository appointments, AppUserRepository users,
                                 ReferenceData ref, YardClock clock) {
        this.maintenance = maintenance;
        this.docks = docks;
        this.appointments = appointments;
        this.users = users;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("docks", docks.findByActiveTrueOrderByCode());
        model.addAttribute("list", maintenance.listAll());
        model.addAttribute("slotTimes", SLOT_TIMES);
        model.addAttribute("today", clock.today());
        model.addAttribute("clock", clock);
        model.addAttribute("ref", ref);
        return "maintenance/page";
    }

    @PostMapping
    public String register(@Valid @ModelAttribute MaintenanceForm form, BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
        DockMaintenance m = maintenance.register(form.getDockId(), form.getDate(),
                form.getStartHM(), form.getEndHM(), form.getReason(), CurrentUser.id());
        return "redirect:/maintenance/" + m.getId() + "?registered=1";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        DockMaintenance m = maintenance.get(id);
        Dock dock = docks.findById(m.getDockId())
                .orElseThrow(() -> new com.example.dockyard.service.BusinessRuleException("月台不存在"));

        List<Appointment> impacted = maintenance.impactList(m);
        List<ActionRow> actions = new ArrayList<>();
        for (AppointmentReschedule r : maintenance.actionsOf(id)) {
            Appointment a = appointments.findById(r.getAppointmentId()).orElse(null);
            String actor = users.findById(r.getActedBy())
                    .map(u -> u.getDisplayName() + "（" + u.getRole().name() + "）")
                    .orElse("用户#" + r.getActedBy());
            actions.add(new ActionRow(r, a, actor));
        }

        model.addAttribute("m", m);
        model.addAttribute("dock", dock);
        model.addAttribute("creatorName", users.findById(m.getCreatedBy())
                .map(u -> u.getDisplayName()).orElse("用户#" + m.getCreatedBy()));
        model.addAttribute("impacted", impacted);
        model.addAttribute("pendingList", maintenance.pendingOf(m));
        model.addAttribute("actions", actions);
        model.addAttribute("slotTimes", SLOT_TIMES);
        model.addAttribute("clock", clock);
        model.addAttribute("ref", ref);
        model.addAttribute("today", clock.today());
        return "maintenance/detail";
    }

    @PostMapping("/{id}/reschedule")
    public String reschedule(@PathVariable Long id,
                             @RequestParam Long apptId,
                             @Valid @ModelAttribute RescheduleForm form, BindingResult br) {
        if (br.hasErrors()) {
            throw new com.example.dockyard.service.BusinessRuleException(WebForms.firstError(br));
        }
        maintenance.reschedule(apptId, id, form.getTargetDate(), form.getTargetHM(),
                form.getReason(), CurrentUser.id());
        return "redirect:/maintenance/" + id + "?rescheduled=1";
    }

    @PostMapping("/{id}/pending")
    public String pending(@PathVariable Long id, @RequestParam Long apptId, @RequestParam String reason) {
        maintenance.markPending(apptId, id, reason, CurrentUser.id());
        return "redirect:/maintenance/" + id + "?pending=1";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam String reason) {
        maintenance.cancel(id, reason, CurrentUser.id());
        return "redirect:/maintenance/" + id + "?cancelled=1";
    }

    /** 处置记录页面行：处置本体 + 当前预约（车牌/码）+ 操作人姓名 */
    public record ActionRow(AppointmentReschedule record, Appointment appt, String actorName) {}
}
