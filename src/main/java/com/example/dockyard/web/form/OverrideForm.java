package com.example.dockyard.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 调度员插单表单：在预约字段之外必须选择承运商并写明插单原因。 */
public class OverrideForm extends BookingForm {

    @NotNull(message = "插单必须选择承运商")
    private Long carrierId;

    @NotBlank(message = "插单必须写明原因")
    @Size(max = 500, message = "插单原因最长 500 个字符")
    private String reason;

    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
