package com.example.dockyard.web;

import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.WarehouseOperationService;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 仓库：靠台 → 开始卸货 → 完成 → 出场（出场自动核费）。 */
@Controller
@RequestMapping("/warehouse")
public class WarehouseController {

    private final WarehouseOperationService warehouse;
    private final AppointmentRepository appointments;
    private final ReferenceData ref;
    private final YardClock clock;

    public WarehouseController(WarehouseOperationService warehouse,
                               AppointmentRepository appointments, ReferenceData ref, YardClock clock) {
        this.warehouse = warehouse;
        this.appointments = appointments;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("active", appointments.findAllInYard());
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        return "warehouse/page";
    }

    @PostMapping("/{id}/dock")
    public String dock(@PathVariable Long id, @RequestParam Long dockId) {
        warehouse.dock(id, dockId, CurrentUser.id());
        return "redirect:/warehouse";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id) {
        warehouse.startUnload(id, CurrentUser.id());
        return "redirect:/warehouse";
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id) {
        warehouse.complete(id, CurrentUser.id());
        return "redirect:/warehouse";
    }

    @PostMapping("/{id}/exit")
    public String exit(@PathVariable Long id) {
        warehouse.exit(id, CurrentUser.id());
        return "redirect:/appointments/" + id;
    }
}
