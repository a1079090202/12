package com.example.dockyard.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 承运商提起异议表单 */
public class DisputeRaiseForm {

    @NotNull(message = "缺少预约单")
    private Long appointmentId;

    @NotBlank(message = "异议原因不能为空")
    @Size(max = 1000, message = "异议原因最长 1000 个字符")
    private String reason;

    public Long getAppointmentId() { return appointmentId; }
    public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
