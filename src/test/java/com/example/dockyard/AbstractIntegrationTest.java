package com.example.dockyard;

import com.example.dockyard.bootstrap.DataInitializer;
import com.example.dockyard.domain.AppUser;
import com.example.dockyard.repo.AppUserRepository;
import com.example.dockyard.security.LoginUser;
import com.example.dockyard.service.YardClock;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * 集成测试基类：整个套件共用一个内嵌 PostgreSQL（Zonky，无需本机 Docker），
 * 每个用例开始前清空全部业务表并重新执行启动播种，保证用例之间互不污染
 * （例如异议调整过金额的样例单不会影响后续对账用例）。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.seed.sample", () -> "true");
        // 测试固定使用该初始密码（生产默认随机生成）
        registry.add("app.init.password", () -> "dock1234");
        // 显式钉住用例依赖的业务规则参数：application.yml 默认值日后调整不会
        // 悄悄改变测试口径（槽长 30 分钟、免费 90 分钟、迟到宽限 45 分钟）。
        // 槽位容量不在这里配置：它由“当时可用（未停用）月台数”动态推导（普通 4/冷藏 1/大件 1）。
        registry.add("app.slot.length-minutes", () -> "30");
        registry.add("app.fee.free-minutes", () -> "90");
        registry.add("app.gate.late-tolerance-minutes", () -> "45");
    }

    @Autowired protected AppUserRepository userRepository;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected DataInitializer dataInitializer;
    @Autowired protected YardClock yardClock;

    @BeforeEach
    void resetDatabaseAndSeed() {
        SecurityContextHolder.clearContext();
        yardClock.setClock(Clock.system(YardClock.ZONE));
        // 只清数据，不删表（Flyway 结构保留）；CASCADE 处理外键，RESTART IDENTITY 复位主键
        jdbcTemplate.execute("""
                TRUNCATE TABLE appointment_reschedule, dock_maintenance, dispute, fee_segment,
                             fee_settlement, operation_event, appointment, carrier_daily_rate,
                             app_user, carrier, dock
                RESTART IDENTITY CASCADE
                """);
        jdbcTemplate.execute("ALTER SEQUENCE appt_code_seq RESTART WITH 1");
        try {
            dataInitializer.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 在服务层调用前以指定用户名建立登录上下文（满足 @PreAuthorize） */
    protected void loginAs(String username) {
        AppUser u = userRepository.findByUsername(username).orElseThrow();
        LoginUser principal = new LoginUser(u);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    protected Long userId(String username) {
        return userRepository.findByUsername(username).map(AppUser::getId).orElseThrow();
    }

    protected Long carrierIdOf(String username) {
        return userRepository.findByUsername(username).map(AppUser::getCarrierId).orElseThrow();
    }
}
