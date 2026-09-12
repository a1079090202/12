package com.example.dockyard.web;

import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.DisputeService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
@Validated
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
    public String raise(@RequestParam Long appointmentId,
                        @RequestParam @NotBlank(message = "异议原因不能为空")
                        @Size(max = 1000, message = "异议原因最长 1000 个字符") String reason) {
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
                         @RequestParam @NotNull(message = "调整后金额不能为空")
                         @DecimalMin(value = "0.00", message = "调整后金额不能为负") BigDecimal adjustedAmount,
                         @RequestParam(required = false) @Size(max = 1000) String note) {
        disputes.adjust(id, adjustedAmount, note, CurrentUser.id());
        return "redirect:/disputes";
    }
}
