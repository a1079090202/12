package com.example.dockyard.web;

import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
@RequestMapping("/disputes")
public class DisputeController {

    private final DisputeService disputes;
    private final ReferenceData ref;
    private final YardClock clock;

    public DisputeController(DisputeService disputes, ReferenceData ref, YardClock clock) {
        this.disputes = disputes;
        this.ref = ref;
        this.clock = clock;
    }

    /** 调度员：待处理异议列表 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("open", disputes.openDisputes());
        model.addAttribute("ref", ref);
        model.addAttribute("clock", clock);
        return "dispute/list";
    }

    /** 承运商：对本司某单提异议 */
    @PostMapping("/raise")
    public String raise(@RequestParam Long appointmentId, @RequestParam String reason) {
        LoginUser me = CurrentUser.get();
        disputes.raise(appointmentId, reason, me.getCarrierId(), me.getId());
        return "redirect:/appointments/" + appointmentId;
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false) String note) {
        disputes.reject(id, note, CurrentUser.id());
        return "redirect:/disputes";
    }

    @PostMapping("/{id}/adjust")
    public String adjust(@PathVariable Long id,
                         @RequestParam BigDecimal adjustedAmount,
                         @RequestParam(required = false) String note) {
        disputes.adjust(id, adjustedAmount, note, CurrentUser.id());
        return "redirect:/disputes";
    }
}
