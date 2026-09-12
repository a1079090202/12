package com.example.dockyard.web;

import com.example.dockyard.domain.FeeSegment;
import com.example.dockyard.domain.FeeSettlement;
import com.example.dockyard.security.CurrentUser;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.FeeService;
import com.example.dockyard.service.ReferenceData;
import com.example.dockyard.service.YardClock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 当天核费明细下载（CSV，带 BOM，Excel 可直接打开）。
 * 金额取 fee_settlement.final_amount（异议调整后金额），
 * 同时输出 original_amount 与各费率日分段，保证与页面数字逐格对得上。
 */
@RestController
@RequestMapping("/fees")
public class FeeController {

    private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FeeService fees;
    private final ReferenceData ref;
    private final YardClock clock;

    public FeeController(FeeService fees, ReferenceData ref, YardClock clock) {
        this.fees = fees;
        this.ref = ref;
        this.clock = clock;
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download() {
        LoginUser me = CurrentUser.get();
        LocalDate day = clock.today();
        List<FeeSettlement> list = fees.settlementsOfDay(day);
        // 承运商只能下载本司明细；其余角色看到当天全部
        if (me.getRole() == com.example.dockyard.domain.Role.CARRIER) {
            list = list.stream().filter(f -> f.getCarrierId().equals(me.getCarrierId())).toList();
        }

        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，Excel 直接打开不乱码
        sb.append("预约码,承运商,车牌,进场时间,靠台时间,等待分钟,免费分钟,计费分钟,")
          .append("原始金额,最终金额,核费状态,费率分段快照,核费时间\n");

        for (FeeSettlement f : list) {
            List<FeeSegment> segs = fees.segments(f.getId());
            String segText = segs.isEmpty()
                    ? "无（免费时长内）"
                    : segs.stream()
                        .map(s -> s.getSegmentDate() + "@" + s.getRatePerHour() + "元/时×"
                                + s.getMinutes() + "分=" + s.getAmount() + "元")
                        .collect(Collectors.joining(" | "));
            var brief = fees.appointmentBrief(f.getAppointmentId());
            sb.append(row(f, brief, segText));
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "fee-detail-" + day.format(FILE_DAY) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private String row(FeeSettlement f, FeeService.AppointmentBrief brief, String segText) {
        return String.join(",",
                csv(brief.code()),
                csv(ref.carrierName(f.getCarrierId())),
                csv(f.getPlateNo()),
                csv(clock.formatDateTime(brief.arrivedAt())),
                csv(clock.formatDateTime(brief.dockedAt())),
                String.valueOf(f.getWaitMinutes()),
                String.valueOf(f.getFreeMinutes()),
                String.valueOf(f.getChargeableMinutes()),
                f.getOriginalAmount().toPlainString(),
                f.getFinalAmount().toPlainString(),
                f.getStatus().name(),
                csv(segText),
                clock.formatDateTime(f.getGeneratedAt()))
                + "\n";
    }

    private String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
