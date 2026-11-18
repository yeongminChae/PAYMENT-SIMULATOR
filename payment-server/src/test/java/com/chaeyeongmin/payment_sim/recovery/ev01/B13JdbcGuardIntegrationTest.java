package com.chaeyeongmin.payment_sim.recovery.ev01;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class B13JdbcGuardIntegrationTest {
    @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17");
    @Test void actualBackendAndEveryConnectionGuardRejectMismatchedIdentity() throws Exception {
        var jdbc=new JdbcTemplate(new SingleConnectionDataSource(DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()),true));
        var cfg=B13RecoveryLauncher.JSON.createObjectNode();cfg.set("identity",B13RecoveryLauncher.JSON.readTree(jdbc.queryForObject(B13RecoveryLauncher.identitySql(),String.class)));
        B13RecoveryLauncher.identity(jdbc,cfg);
        jdbc.execute(B13RecoveryLauncher.connectionGuard(cfg));
        cfg.withObject("/identity").put("system","0");
        assertThatThrownBy(()->B13RecoveryLauncher.identity(jdbc,cfg)).hasMessageContaining("identity mismatch");
        assertThatThrownBy(()->jdbc.execute(B13RecoveryLauncher.connectionGuard(cfg))).hasMessageContaining("B13 backend mismatch");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isZero();
    }
}
