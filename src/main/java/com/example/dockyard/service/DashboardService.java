package com.example.dockyard.service;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.AppointmentRepository;
import com.example.dockyard.repo.DockRepository;
import com.example.dockyard.repo.FeeSettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** 首页看板聚合：6 个月台现状、等待车辆、今日平均周转时间、今日核费汇总。纯查询。 */
@Service
public class DashboardService {

    private final DockRepository docks;
    private final AppointmentRepository appointments;
    private final FeeSettlementRepository settlements;
    private final YardClock clock;

    public DashboardService(DockRepository docks, AppointmentRepository appointments,
                            FeeSettlementRepository settlements, YardClock clock) {
        this.docks = docks;
        this.appointments = appointments;
        this.settlements = settlements;
        this.clock = clock;
    }

    public record DockStatus(Dock dock, Appointment appt, String phase) {}

    public record View(List<DockStatus> dockStatuses, List<Appointment> waiting,
                       long avgTurnSeconds, String avgTurnText, long turnedCount,
                       int inYardCount, List<FeeSettlement> todayFees,
                       java.math.BigDecimal todayFeeTotal, LocalDate date) {}

    @Transactional(readOnly = true)
    public View build(LocalDate date) {
        if (date == null) {
            date = clock.today();
        }
        List<Dock> allDocks = docks.findByActiveTrueOrderByCode();
        List<Appointment> inYard = appointments.findAllInYard();

        Map<Long, Appointment> byDock = new HashMap<>();
        for (Appointment a : inYard) {
            if (a.getAssignedDockId() != null) {
                byDock.put(a.getAssignedDockId(), a);
            }
        }

        List<DockStatus> statuses = new ArrayList<>();
        for (Dock d : allDocks) {
            Appointment a = byDock.get(d.getId());
            statuses.add(new DockStatus(d, a, a == null ? "空闲" : phaseLabel(a)));
        }

        // 等待车辆：已进场未派台优先，已叫号未靠台次之（与队列排序一致）
        List<Appointment> waiting = appointments.findWaitingOrdered();

        // 今日平均周转时间：出场时刻 - 进场时刻（今日已出场车辆）
        Instant dayStart = clock.dayStart(date);
        Instant dayEnd = clock.dayEnd(date);
        List<Appointment> exitedToday = appointments.findExitedBetween(dayStart, dayEnd);
        long totalSeconds = 0;
        long count = 0;
        for (Appointment a : exitedToday) {
            if (a.getArrivedAt() != null && a.getExitedAt() != null) {
                totalSeconds += Duration.between(a.getArrivedAt(), a.getExitedAt()).getSeconds();
                count++;
            }
        }
        long avgSeconds = count == 0 ? 0 : totalSeconds / count;
        String avgText = formatDuration(avgSeconds);

        var fees = settlements.findByGeneratedAtBetweenOrderByGeneratedAtAsc(dayStart, dayEnd);
        java.math.BigDecimal feeTotal = fees.stream()
                .map(FeeSettlement::getFinalAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        return new View(statuses, waiting, avgSeconds, avgText, count, inYard.size(),
                fees, feeTotal, date);
    }

    /** 格式化成“x 小时 y 分钟”或“y 分钟” */
    public static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) {
            return h + " 小时 " + m + " 分钟";
        }
        return m + " 分钟";
    }

    private String phaseLabel(Appointment a) {
        return switch (a.getStatus()) {
            case CALLED -> "已叫号";
            case DOCKED -> "已靠台";
            case UNLOADING -> "卸货中";
            case COMPLETED -> "待出场";
            default -> "占用中";
        };
    }
}
