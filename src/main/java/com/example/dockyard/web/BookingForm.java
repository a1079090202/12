package com.example.dockyard.web;

import com.example.dockyard.domain.DockType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 预约 / 插单表单参数及服务端校验。
 * 长度上限与 V1__init_schema.sql 的 varchar 列保持一致，
 * 避免超长输入直接落库抛 DataIntegrityViolationException。
 */
public class BookingForm {

    @NotBlank(message = "订单号不能为空")
    @Size(max = 64, message = "订单号最长 64 个字符")
    private String orderNo;

    @NotBlank(message = "车牌号不能为空")
    @Size(max = 16, message = "车牌号最长 16 个字符")
    private String plateNo;

    @NotBlank(message = "司机姓名不能为空")
    @Size(max = 64, message = "司机姓名最长 64 个字符")
    private String driverName;

    @Size(max = 32, message = "司机电话最长 32 个字符")
    private String driverPhone;

    @NotBlank(message = "货类不能为空")
    @Size(max = 64, message = "货类最长 64 个字符")
    private String cargoType;

    @NotNull(message = "必须选择月台类型")
    private DockType dockType;

    @NotNull(message = "必须选择到达日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate slotDate;

    @NotBlank(message = "必须选择到达时段")
    @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "时段必须是 HH:mm 的 30 分钟整点")
    private String slotTime;

    /** 仅插单使用；普通预约不提交。非空校验在调度插单入口单独处理。 */
    @Size(max = 500, message = "插单原因最长 500 个字符")
    private String reason;

    /** 仅插单使用：被插单的承运商 */
    private Long carrierId;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getDriverPhone() { return driverPhone; }
    public void setDriverPhone(String driverPhone) { this.driverPhone = driverPhone; }
    public String getCargoType() { return cargoType; }
    public void setCargoType(String cargoType) { this.cargoType = cargoType; }
    public DockType getDockType() { return dockType; }
    public void setDockType(DockType dockType) { this.dockType = dockType; }
    public LocalDate getSlotDate() { return slotDate; }
    public void setSlotDate(LocalDate slotDate) { this.slotDate = slotDate; }
    public String getSlotTime() { return slotTime; }
    public void setSlotTime(String slotTime) { this.slotTime = slotTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
}
