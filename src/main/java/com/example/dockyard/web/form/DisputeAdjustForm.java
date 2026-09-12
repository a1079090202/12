package com.example.dockyard.web.form;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 调度员异议成立、调整金额表单。上限与 numeric(12,2) 列宽对齐。 */
public class DisputeAdjustForm {

    @NotNull(message = "调整后金额不能为空")
    @DecimalMin(value = "0.00", message = "调整后金额不能为负")
    @DecimalMax(value = "9999999999.99", message = "调整后金额超出允许上限")
    private BigDecimal adjustedAmount;

    @Size(max = 1000, message = "处理备注最长 1000 个字符")
    private String note;

    public BigDecimal getAdjustedAmount() { return adjustedAmount; }
    public void setAdjustedAmount(BigDecimal adjustedAmount) { this.adjustedAmount = adjustedAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
