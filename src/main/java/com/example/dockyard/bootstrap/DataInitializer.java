package com.example.dockyard.bootstrap;

import com.example.dockyard.domain.*;
import com.example.dockyard.repo.*;
import com.example.dockyard.service.FeeService;
import com.example.dockyard.service.YardClock;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 启动初始化（库为空时执行，可重复启动不重复造数）：
 *  - 4 类角色账号；2 家承运商；6 个月台；
 *  - 两家承运商今/明两日费率（跨费率日期演示用）；
 *  - 20 条今日样例预约：16 条已预约、1 条等候中、1 条卸货中、
 *    1 条已出场（含核费单，可直接对账/提异议），另 1 条已插单。
 * 初始密码统一 dock1234，见 README。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String INIT_PASSWORD = "dock1234";

    private final AppUserRepository users;
    private final CarrierRepository carriers;
    private final CarrierDailyRateRepository rates;
    private final DockRepository docks;
    private final AppointmentRepository appointments;
    private final FeeService feeService;
    private final PasswordEncoder encoder;
    private final YardClock clock;
    private final EntityManager em;

    @Value("${app.seed.sample:true}")
    private boolean seedSample;

    public DataInitializer(AppUserRepository users, CarrierRepository carriers,
                           CarrierDailyRateRepository rates, DockRepository docks,
                           AppointmentRepository appointments, FeeService feeService,
                           PasswordEncoder encoder, YardClock clock, EntityManager em) {
        this.users = users;
        this.carriers = carriers;
        this.rates = rates;
        this.docks = docks;
        this.appointments = appointments;
        this.feeService = feeService;
        this.encoder = encoder;
        this.clock = clock;
        this.em = em;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.count() > 0) {
            return;
        }

        Carrier c1 = carrier("SD", "顺达物流");
        Carrier c2 = carrier("LX", "冷鲜运输");

        // 费率：今日 60 / 次日 90（C1）；今日 80 / 次日 100（C2）——用于跨费率日期分段
        rate(c1, clock.today(), "60.00");
        rate(c1, clock.today().plusDays(1), "90.00");
        rate(c2, clock.today(), "80.00");
        rate(c2, clock.today().plusDays(1), "100.00");

        dock("D1", "1号月台", DockType.STANDARD);
        dock("D2", "2号月台", DockType.STANDARD);
        dock("D3", "3号月台", DockType.STANDARD);
        dock("D4", "4号月台", DockType.STANDARD);
        dock("D5", "5号冷藏月台", DockType.COLD);
        dock("D6", "6号大件月台", DockType.OVERSIZE);

        Long c1Id = c1.getId(), c2Id = c2.getId();
        user("carrier1", "顺达-调度联系人", Role.CARRIER, c1Id);
        user("carrier2", "冷鲜-调度联系人", Role.CARRIER, c2Id);
        Long guardId = user("guard", "门卫老王", Role.GUARD, null);
        Long whId = user("warehouse", "仓管小李", Role.WAREHOUSE, null);
        Long dispId = user("dispatcher", "调度老赵", Role.DISPATCHER, null);

        log.info("初始化账号完成，初始密码均为 {}", INIT_PASSWORD);

        if (seedSample && appointments.count() == 0) {
            seedAppointments(c1Id, c2Id, guardId, whId, dispId);
            log.info("已写入 20 条样例预约");
        }
    }

    private void seedAppointments(Long c1, Long c2, long guardId, long whId, long dispId) {
        LocalDate today = clock.today();
        String[] cargoStd = {"常温食品", "日化百货", "服装", "电子配件"};
        String[] cargoCold = {"冷冻肉类", "冷藏乳制品"};

        // 16 条正常预约，分散在 08:00-10:00 的各 30 分钟槽，每槽不超过 4 单，留出容量
        String[] times = {"08:00", "08:00", "08:00", "08:00",
                          "08:30", "08:30", "08:30", "08:30",
                          "09:00", "09:00", "09:00", "09:00",
                          "09:30", "09:30", "09:30", "10:00"};
        for (int i = 0; i < times.length; i++) {
            Long carrierId = (i % 3 == 0) ? c2 : c1;
            DockType type = (carrierId.equals(c2)) ? DockType.COLD : DockType.STANDARD;
            if (i == 11) {
                type = DockType.OVERSIZE;
            }
            Instant start = clock.truncateToSlot(clock.parseDateTime(today, times[i]), 30);
            saveBooked(carrierId, "SO-" + today.toString().replace("-", "") + "-" + (i + 1),
                    "沪A" + String.format("%04d", 1001 + i), "司机" + (i + 1),
                    type == DockType.COLD ? cargoCold[i % 2] : cargoStd[i % 4],
                    type, start, AppointmentStatus.BOOKED, null, null);
        }

        // 1 条插单（08:30 槽，写原因）
        Instant overSlot = clock.truncateToSlot(clock.parseDateTime(today, "08:30"), 30);
        Appointment over = saveBooked(c1, "SO-URGENT-01", "沪B09001", "加急司机",
                "加急电子配件", DockType.STANDARD, overSlot,
                AppointmentStatus.OVERRIDDEN, "客户产线停工待料，调度插单", dispId);

        // 1 条已进场在等候（20 分钟前到）
        Instant arrived = clock.now().minusSeconds(20 * 60);
        Instant waitSlot = clock.truncateToSlot(arrived, 30);
        Appointment waiting = saveBooked(c1, "SO-WAIT-01", "沪A00021", "等候司机",
                "日化百货", DockType.STANDARD, waitSlot, AppointmentStatus.GATED_IN, null, null);
        waiting.setArrivedAt(arrived);
        appointments.save(waiting);

        // 1 条卸货中（占用 D1）
        Instant unloadSlot = clock.truncateToSlot(clock.parseDateTime(today, "07:00"), 30);
        Appointment unloading = saveBooked(c1, "SO-LIVE-01", "沪A00022", "作业司机",
                "常温食品", DockType.STANDARD, unloadSlot, AppointmentStatus.UNLOADING, null, null);
        unloading.setAssignedDockId(docks.findByActiveTrueOrderByCode().get(0).getId());
        unloading.setCalledAt(clock.parseDateTime(today, "08:20"));
        unloading.setArrivedAt(clock.parseDateTime(today, "08:05"));
        unloading.setDockedAt(clock.parseDateTime(today, "08:25"));
        unloading.setUnloadStartAt(clock.parseDateTime(today, "08:30"));
        unloading.setWaitMinutes(20);
        appointments.save(unloading);

        // 1 条已出场：等待 150 分钟，免费 90，计费 60 分钟（今日费率 60 元/时 -> 60.00 元）
        Instant exitSlot = clock.truncateToSlot(clock.parseDateTime(today, "06:30"), 30);
        Appointment exited = saveBooked(c1, "SO-DONE-01", "沪A00023", "离场司机",
                "服装", DockType.STANDARD, exitSlot, AppointmentStatus.EXITED, null, null);
        exited.setArrivedAt(clock.parseDateTime(today, "09:00"));
        exited.setCalledAt(clock.parseDateTime(today, "11:25"));
        exited.setAssignedDockId(docks.findByActiveTrueOrderByCode().get(1).getId());
        exited.setDockedAt(clock.parseDateTime(today, "11:30"));
        exited.setWaitMinutes(150);
        exited.setUnloadStartAt(clock.parseDateTime(today, "11:35"));
        exited.setCompletedAt(clock.parseDateTime(today, "12:10"));
        exited.setExitedAt(clock.parseDateTime(today, "12:15"));
        appointments.save(exited);
        feeService.settle(exited, whId);
    }

    private Appointment saveBooked(Long carrierId, String orderNo, String plate, String driver,
                                   String cargo, DockType type, Instant slotStart,
                                   AppointmentStatus status, String overrideReason, Long overrideBy) {
        Appointment a = new Appointment();
        a.setCode(nextCode());
        a.setCarrierId(carrierId);
        a.setOrderNo(orderNo);
        a.setPlateNo(plate);
        a.setDriverName(driver);
        a.setCargoType(cargo);
        a.setDockType(type);
        a.setSlotStart(slotStart);
        a.setSlotEnd(slotStart.plusSeconds(30 * 60));
        a.setStatus(status);
        a.setOverrideReason(overrideReason);
        a.setOverriddenBy(overrideBy);
        a.setCreatedBy(overrideBy);
        return appointments.save(a);
    }

    private Carrier carrier(String code, String name) {
        Carrier c = new Carrier();
        c.setCode(code);
        c.setName(name);
        return carriers.save(c);
    }

    private void rate(Carrier c, LocalDate date, String perHour) {
        CarrierDailyRate r = new CarrierDailyRate();
        r.setCarrierId(c.getId());
        r.setRateDate(date);
        r.setRatePerHour(new BigDecimal(perHour));
        rates.save(r);
    }

    private void dock(String code, String name, DockType type) {
        Dock d = new Dock();
        d.setCode(code);
        d.setName(name);
        d.setDockType(type);
        docks.save(d);
    }

    private Long user(String username, String displayName, Role role, Long carrierId) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setRole(role);
        u.setCarrierId(carrierId);
        u.setPasswordHash(encoder.encode(INIT_PASSWORD));
        return users.save(u).getId();
    }

    private String nextCode() {
        Long seq = ((Number) em.createNativeQuery("select nextval('appt_code_seq')")
                .getSingleResult()).longValue();
        String dayPart = clock.today().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        return "YY" + dayPart + String.format("%04d", seq % 10000);
    }
}
