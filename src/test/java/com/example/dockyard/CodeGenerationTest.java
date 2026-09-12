package com.example.dockyard;

import com.example.dockyard.service.AppointmentCodeGenerator;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预约码生成：运行时（AppointmentService）与播种（DataInitializer）共用同一个
 * AppointmentCodeGenerator，这里直接钉住它的契约：
 *  - 格式统一为 YY + 槽位上海日 yyMMdd + 至少 6 位序列（不足补零，超长不截断）；
 *  - 日期部分取槽位所在的上海自然日，而不是生成当天；
 *  - 序列跨过 10000 后仍不重号（旧实现 seq % 10000 会在同日与旧码撞号）。
 */
class CodeGenerationTest extends AbstractIntegrationTest {

    @Autowired AppointmentCodeGenerator codeGenerator;

    private Instant slotAt(LocalDate day, String hm) {
        return ZonedDateTime.of(day, LocalTime.parse(hm), YardClock.ZONE).toInstant();
    }

    @Test
    void batch_codes_are_unique_and_follow_single_format() {
        LocalDate day = LocalDate.now(YardClock.ZONE).plusDays(26);
        Instant slot = slotAt(day, "13:00");

        // 期望：YY + 两位年+两位月+两位日 + 6 位起的序列
        String dayPart = String.format("%02d%02d%02d",
                day.getYear() % 100, day.getMonthValue(), day.getDayOfMonth());
        Pattern format = Pattern.compile("^YY" + dayPart + "\\d{6,}$");

        int n = 500;
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String code = codeGenerator.next(slot);
            assertThat(code).matches(format);
            codes.add(code);
        }
        assertThat(codes).hasSize(n); // 无重号
    }

    @Test
    void day_part_comes_from_slot_date_not_generation_date() {
        LocalDate slotDay = LocalDate.now(YardClock.ZONE).plusDays(30);
        String code = codeGenerator.next(slotAt(slotDay, "09:30"));
        String expectedDayPart = String.format("%02d%02d%02d",
                slotDay.getYear() % 100, slotDay.getMonthValue(), slotDay.getDayOfMonth());
        assertThat(code).startsWith("YY" + expectedDayPart);
    }

    @Test
    void sequence_beyond_10000_does_not_collide_with_same_day_codes() {
        LocalDate day = LocalDate.now(YardClock.ZONE).plusDays(27);
        Instant slot = slotAt(day, "08:00");

        // 序列低位段（1..5）的码
        Set<String> low = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            low.add(codeGenerator.next(slot));
        }

        // 把序列直接拨到 10000 开外：旧实现 seq % 10000 会在这里绕回 0000/0001… 与低位段撞号
        jdbcTemplate.execute("ALTER SEQUENCE appt_code_seq RESTART WITH 10000");
        Set<String> high = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String code = codeGenerator.next(slot);
            assertThat(code).matches("^YY\\d{6}\\d{6,}$"); // 超过 4 位不截断
            high.add(code);
        }

        assertThat(high).doesNotContainAnyElementsOf(low);
        assertThat(high).hasSize(5);
    }
}
