package com.example.dockyard.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 门卫凭预约码放行表单（YY + 6 位日期 + 4 位序号，只含字母数字与连字符） */
public class GateInForm {

    @NotBlank(message = "预约码不能为空")
    @Size(max = 24, message = "预约码最长 24 个字符")
    @Pattern(regexp = "^[A-Za-z0-9-]{3,24}$", message = "预约码格式不正确")
    private String code;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
