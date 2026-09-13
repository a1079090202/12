package com.example.dockyard.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 受影响预约改期表单：目标时段（日期 + 30 分钟整点）+ 必填改期原因。 */
public class RescheduleForm {

    @NotNull(message = "必须选择改期日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate targetDate;

    @NotBlank(message = "必须选择改期时间")
    @Pattern(regexp = "^([01]\\d|2[0-3]):(00|30)$", message = "改期时间必须是 30 分钟整点")
    private String targetHM;

    @NotBlank(message = "改期必须写明原因")
    @Size(max = 500, message = "改期原因最长 500 个字符")
    private String reason;

    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public String getTargetHM() { return targetHM; }
    public void setTargetHM(String targetHM) { this.targetHM = targetHM; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
