package com.example.dockyard;

import com.example.dockyard.service.AppointmentCodeGenerator;
import com.example.dockyard.service.YardClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预约码生成：运行时与播种共用同一生成器。
 *  - 连续批量生成不重号（旧实现 seq % 10000 单日过万会撞码）；
 *  - 格式统一为 YY + yyMMdd + 至少 6 位序列，按预约槽位所在上海自然日。
 */
class CodeGenerationTest extends AbstractIntegrationTest {

    @Autowired AppointmentCodeGenerator codeGenerator;
    @Autowired YardClock clock;

    @Test
    void codes_are_unique_and_follow_single_format() {
        LocalDate day = LocalDate.now(YardClock.ZONE).plusDays(26);
        Instant slot = ZonedDateTime.of(day, LocalTime.parse("13:00"), ZoneId.of("Asia/Shanghai")).toInstant();

        // 期望：YY + 两位年+两位月+两位日 + 6 位起的序列
        int yy = day.getYear() % 100;
        String expectedDayPart = String.format("%02d%02d%02d", yy, day.getMonthValue(), day.getDayOfMonth());
        Pattern format = Pattern.compile("^YY" + expectedDayPart + "\\d{6,}$");

        int n = 500;
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String code = codeGenerator.next(slot);
            assertThat(code).matches(format);
            codes.add(code);
        }
        assertThat(codes).hasSize(n); // 无重号
    }
}
