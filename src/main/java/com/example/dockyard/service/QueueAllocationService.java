package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DockMaintenanceRepository;
import com.example.dockyard.repo.DockRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 排队分配（独立服务，只负责叫号与月台分配）：
 *  - 等候队列按实际进场先后排序；
 *  - 同一月台被并发派车时：先对 dock 行 SELECT ... FOR UPDATE 串行化，
 *    后到事务在锁上等待，拿到锁后复查占用，干净地拒绝；
 *    数据库部分唯一索引 uq_dock_occupancy 作为最终兜底；
 *  - 月台类型必须与预约所需类型一致；
 *  - 处于保养停用窗（SCHEDULED）的月台不参与分配：手选明确拒绝，自动分配跳过。
 */
@Service
public class QueueAllocationService {

    private final AppointmentRepository appointments;
    private final DockRepository docks;
    private final DockMaintenanceRepository maintenances;
    private final EventLogService events;
    private final YardClock clock;

    public QueueAllocationService(AppointmentRepository appointments, DockRepository docks,
                                  DockMaintenanceRepository maintenances,
                                  EventLogService events, YardClock clock) {
        this.appointments = appointments;
        this.docks = docks;
        this.maintenances = maintenances;
        this.events = events;
        this.clock = clock;
    }

    /** 等候区车辆（GATED_IN 在前，按到达时间） */
    @Transactional(readOnly = true)
    public List<Appointment> waitingQueue() {
        return appointments.findWaitingOrdered();
    }

    /**
     * 调度员叫号并派台。dockId 为 null 时按类型自动分配一个空闲月台。
     */
    @Transactional
    @PreAuthorize("hasRole('DISPATCHER')")
    public Appointment callToDock(Long apptId, Long requestedDockId, Long actorId) {
        // 先锁预约行：同一预约被两个调度员同时叫号时在此串行
        Appointment appt = appointments.lockById(apptId)
                .orElseThrow(() -> new BusinessRuleException("预约不存在"));
        if (appt.getStatus() != AppointmentStatus.GATED_IN) {
            throw new BusinessRuleException("车辆当前状态为「" + appt.getStatus().getLabel()
                    + "」，只有等候中的车辆可以叫号派台");
        }

        Dock dock;
        if (requestedDockId != null) {
            dock = lockAndCheckDock(requestedDockId, appt);
        } else {
            dock = autoAllocate(appt);
        }

        appt.setAssignedDockId(dock.getId());
        appt.setCalledAt(clock.now());
        appt.setStatus(AppointmentStatus.CALLED);
        appointments.save(appt);
        events.record(appt.getId(), EventType.CALL, actorId, dock.getId(),
                "叫号派台 → " + dock.getCode() + "（" + dock.getDockType().getLabel() + "）");
        return appt;
    }

    /** 锁月台行并校验：存在、启用、类型匹配、不在保养停用窗、当前空闲 */
    private Dock lockAndCheckDock(Long dockId, Appointment appt) {
        Dock dock = appointments.lockDockById(dockId)
                .orElseThrow(() -> new BusinessRuleException("月台不存在"));
        if (!dock.isActive()) {
            throw new BusinessRuleException("月台 " + dock.getCode() + " 已停用");
        }
        if (dock.getDockType() != appt.getDockType()) {
            throw new BusinessRuleException("月台 " + dock.getCode() + " 是" + dock.getDockType().getLabel()
                    + "，本车需要的是" + appt.getDockType().getLabel());
        }
        DockMaintenance blocking = activeMaintenance(dockId, clock.now());
        if (blocking != null) {
            throw new BusinessRuleException("月台 " + dock.getCode() + " 保养停用中（至 "
                    + clock.formatTime(blocking.getWindowEnd()) + "：" + blocking.getReason()
                    + "），不能派车", MAINT_BLOCK);
        }
        List<Appointment> occupiers = appointments.findOccupyingDock(dockId);
        if (!occupiers.isEmpty()) {
            Appointment o = occupiers.get(0);
            throw new BusinessRuleException("月台 " + dock.getCode() + " 已被 " + o.getPlateNo()
                    + " 占用（状态：" + o.getStatus().getLabel() + "），禁止双占", OCCUPIED);
        }
        return dock;
    }

    /** 自动分配：依次锁定同类型月台，跳过停用/占用，取第一个可用者 */
    private Dock autoAllocate(Appointment appt) {
        List<Dock> candidates = docks.findByActiveTrueAndDockTypeOrderByCode(appt.getDockType());
        BusinessRuleException last = null;
        for (Dock candidate : candidates) {
            try {
                return lockAndCheckDock(candidate.getId(), appt);
            } catch (BusinessRuleException e) {
                if (e.reasonCode() == OCCUPIED || e.reasonCode() == MAINT_BLOCK) {
                    last = e;
                    continue;
                }
                throw e;
            }
        }
        if (last != null) {
            throw new BusinessRuleException("没有空闲且未停用的" + appt.getDockType().getLabel()
                    + "，请等待其他车辆完成作业或保养结束");
        }
        throw new BusinessRuleException("没有可用的" + appt.getDockType().getLabel());
    }

    /** 该月台在给定时刻是否落在生效停用窗内（半开区间，边界整点即释放） */
    private DockMaintenance activeMaintenance(Long dockId, Instant at) {
        return maintenances.findActiveAt(at).stream()
                .filter(m -> m.getDockId().equals(dockId))
                .findFirst().orElse(null);
    }

    /** BusinessRuleException 的机器可读原因码：自动分配据此决定“跳过下一个” */
    public static final String OCCUPIED = "DOCK_OCCUPIED";
    public static final String MAINT_BLOCK = "DOCK_MAINTENANCE";
}
