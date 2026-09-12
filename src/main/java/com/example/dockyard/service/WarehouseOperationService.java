package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DockRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 仓库作业：靠台 → 开始卸货 → 完成 → 出场。
 * 每一步都记录操作人与时间；状态机保证同一车辆不能重复靠台。
 * 出场同时触发核费（FeeService），核费明细随出场一并落库。
 */
@Service
public class WarehouseOperationService {

    private final AppointmentRepository appointments;
    private final DockRepository docks;
    private final EventLogService events;
    private final YardClock clock;
    private final FeeService feeService;

    public WarehouseOperationService(AppointmentRepository appointments, DockRepository docks,
                                     EventLogService events, YardClock clock, FeeService feeService) {
        this.appointments = appointments;
        this.docks = docks;
        this.events = events;
        this.clock = clock;
        this.feeService = feeService;
    }

    /** 靠台：必须已派台，且实际靠的就是调度分配的月台 */
    @Transactional
    @PreAuthorize("hasRole('WAREHOUSE')")
    public Appointment dock(Long apptId, Long dockId, Long actorId) {
        Appointment appt = mustExist(apptId);
        if (appt.getStatus() != AppointmentStatus.CALLED) {
            throw new BusinessRuleException("车辆当前为「" + appt.getStatus().getLabel()
                    + "」，只有已叫号派台的车辆能靠台，不能重复靠台");
        }
        if (appt.getAssignedDockId() == null || !appt.getAssignedDockId().equals(dockId)) {
            throw new BusinessRuleException("靠台月台与调度派台不一致");
        }
        // 数据库 uq_dock_occupancy 兜底：并发下同月台只能有一单通过
        if (!appointments.findOccupyingDock(dockId).stream()
                .allMatch(o -> o.getId().equals(appt.getId()))) {
            throw new BusinessRuleException("该月台已被其他车辆占用，禁止双占");
        }
        Instant now = clock.now();
        appt.setDockedAt(now);
        long waitMinutes = clock.minutesBetween(appt.getArrivedAt(), now);
        appt.setWaitMinutes((int) waitMinutes);
        appt.setStatus(AppointmentStatus.DOCKED);
        appointments.save(appt);
        events.record(apptId, EventType.DOCK, actorId, dockId,
                "靠台，进场后等待 " + waitMinutes + " 分钟");
        return appt;
    }

    @Transactional
    @PreAuthorize("hasRole('WAREHOUSE')")
    public Appointment startUnload(Long apptId, Long actorId) {
        Appointment appt = mustExist(apptId);
        if (appt.getStatus() != AppointmentStatus.DOCKED) {
            throw new BusinessRuleException("车辆当前为「" + appt.getStatus().getLabel() + "」，尚未靠台");
        }
        appt.setUnloadStartAt(clock.now());
        appt.setStatus(AppointmentStatus.UNLOADING);
        appointments.save(appt);
        events.record(apptId, EventType.START_UNLOAD, actorId, appt.getAssignedDockId(), "开始卸货");
        return appt;
    }

    @Transactional
    @PreAuthorize("hasRole('WAREHOUSE')")
    public Appointment complete(Long apptId, Long actorId) {
        Appointment appt = mustExist(apptId);
        if (appt.getStatus() != AppointmentStatus.UNLOADING) {
            throw new BusinessRuleException("车辆当前为「" + appt.getStatus().getLabel() + "」，未在卸货");
        }
        appt.setCompletedAt(clock.now());
        appt.setStatus(AppointmentStatus.COMPLETED);
        appointments.save(appt);
        events.record(apptId, EventType.COMPLETE, actorId, appt.getAssignedDockId(), "卸货完成");
        return appt;
    }

    /** 出场：状态置 EXITED（释放月台/车辆在场唯一索引），并生成核费单 */
    @Transactional
    @PreAuthorize("hasRole('WAREHOUSE')")
    public Appointment exit(Long apptId, Long actorId) {
        Appointment appt = mustExist(apptId);
        if (appt.getStatus() != AppointmentStatus.COMPLETED) {
            throw new BusinessRuleException("车辆当前为「" + appt.getStatus().getLabel()
                    + "」，只有卸货完成的车辆能出场");
        }
        Instant now = clock.now();
        appt.setExitedAt(now);
        appt.setStatus(AppointmentStatus.EXITED);
        appointments.save(appt);
        events.record(apptId, EventType.EXIT, actorId, appt.getAssignedDockId(), "车辆出场");

        feeService.settle(appt, actorId);
        return appt;
    }

    private Appointment mustExist(Long apptId) {
        return appointments.lockById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
    }
}
