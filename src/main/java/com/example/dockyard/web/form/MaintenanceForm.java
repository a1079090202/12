package com.example.dockyard.web.form;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 月台保养停用登记表单。列宽与 V3 dock_maintenance 对齐。 */
public class MaintenanceForm {

    @NotNull(message = "必须选择月台")
    private Long dockId;

    @NotNull(message = "必须选择停用日期")
    @FutureOrPresent(message = "不能登记过去日期的停用")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotBlank(message = "必须选择开始时间")
    @Pattern(regexp = "^([01]\\d|2[0-3]):(00|30)$", message = "开始时间必须是 30 分钟整点")
    private String startHM;

    @NotBlank(message = "必须选择结束时间")
    @Pattern(regexp = "^([01]\\d|2[0-3]):(00|30)$", message = "结束时间必须是 30 分钟整点")
    private String endHM;

    @NotBlank(message = "停用必须写明原因")
    @Size(max = 500, message = "停用原因最长 500 个字符")
    private String reason;

    public Long getDockId() { return dockId; }
    public void setDockId(Long dockId) { this.dockId = dockId; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getStartHM() { return startHM; }
    public void setStartHM(String startHM) { this.startHM = startHM; }
    public String getEndHM() { return endHM; }
    public void setEndHM(String endHM) { this.endHM = endHM; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
