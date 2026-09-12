package com.example.dockyard.web.form;

import com.example.dockyard.domain.DockType;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 承运商预约表单。所有长度上限与 V1 建表列宽对齐，
 * 非法输入在控制器层即被拒绝（400），不会透传到数据库变成 500。
 */
public class BookingForm {

    @NotBlank(message = "订单号不能为空")
    @Size(max = 64, message = "订单号最长 64 个字符")
    private String orderNo;

    @NotBlank(message = "车牌号不能为空")
    @Size(min = 2, max = 16, message = "车牌号长度需在 2–16 个字符之间")
    private String plateNo;

    @NotBlank(message = "司机姓名不能为空")
    @Size(max = 64, message = "司机姓名最长 64 个字符")
    private String driverName;

    @Size(max = 32, message = "联系电话最长 32 个字符")
    @Pattern(regexp = "^$|^[0-9+\\-() ]{3,32}$", message = "联系电话格式不正确")
    private String driverPhone;

    @NotBlank(message = "货类不能为空")
    @Size(max = 64, message = "货类最长 64 个字符")
    private String cargoType;

    @NotNull(message = "必须选择月台类型")
    private DockType dockType;

    @NotNull(message = "必须选择预约日期")
    @FutureOrPresent(message = "不能预约过去的日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate slotDate;

    @NotBlank(message = "必须选择预约时间")
    @Pattern(regexp = "^([01]\\d|2[0-3]):(00|30)$", message = "预约时间必须是 30 分钟整点")
    private String slotTime;

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
}
