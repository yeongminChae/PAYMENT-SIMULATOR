package com.chaeyeongmin.payment_sim.recovery.ev01;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
class B13GuardTest {
    @Test void fixedManifestAndMissingRows() throws Exception {
        var rows=B13RecoveryLauncher.JSON.readTree(Path.of("../engineering-validation/ev01/b13/targets.json").toFile());
        var targets=B13RecoveryLauncher.targets(rows);
        assertThat(targets).hasSize(13).containsEntry("2376-20261029-2447-0007","NOT_FOUND").doesNotContainKey("2376-20261029-2446-0008");
    }
    @Test void duplicateOrPartialManifestRejected() throws Exception {
        var rows=B13RecoveryLauncher.JSON.readTree("[{\"posTrx\":\"a\",\"attemptSeq\":1,\"outcome\":\"APPROVED\"},{\"posTrx\":\"a\",\"attemptSeq\":1,\"outcome\":\"APPROVED\"}]");
        assertThatThrownBy(()->B13RecoveryLauncher.targets(rows)).hasMessageContaining("duplicate");
        assertThatThrownBy(()->B13RecoveryLauncher.targets(B13RecoveryLauncher.JSON.readTree("[]"))).hasMessageContaining("13/11/2");
    }
    @Test void inquiryOnlyBlocksAllMutationsBeforeDelegate() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=B13RecoveryLauncher.inquiryOnly((proxy,method,args)->{calls.incrementAndGet();return null;});
        assertThatThrownBy(()->gateway.approve(null)).hasMessageContaining("non-Inquiry");
        assertThatThrownBy(()->gateway.cancel(null)).hasMessageContaining("non-Inquiry");
        assertThatThrownBy(()->gateway.reversal(null)).hasMessageContaining("non-Inquiry");
        assertThat(calls.get()).isZero();
        gateway.inquiry(null);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void changedThirteenTargetSetRejected() throws Exception {
        var rows=B13RecoveryLauncher.JSON.readTree(Path.of("../engineering-validation/ev01/b13/targets.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("posTrx","foreign");
        assertThatThrownBy(()->B13RecoveryLauncher.targets(rows)).hasMessageContaining("fixed B13 set mismatch");
    }
    @Test void originalAndOptionUrlsRejectedBeforeConnection() {
        for(String url:new String[]{"jdbc:postgresql://127.0.0.1:5432/payment_sim","jdbc:postgresql://localhost:15432/x","jdbc:postgresql://127.0.0.1:15432/x?options=-csearch_path=public"}) {
            var cfg=B13RecoveryLauncher.JSON.createObjectNode().put("url",url);
            assertThatThrownBy(()->B13RecoveryLauncher.connect(cfg,"unused")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void postgresBackendUsesHostAddressWithStableAlias() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        String sql="SELECT datname,host(client_addr) AS client_addr,usename FROM pg_stat_activity WHERE application_name=?";
        org.mockito.Mockito.when(jdbc.queryForList(sql,"EV01B13_VAN")).thenReturn(java.util.List.of(
            java.util.Map.of("datname","van_sim","client_addr","172.19.0.1","usename","postgres")));
        var cfg=B13RecoveryLauncher.JSON.createObjectNode();
        cfg.withObject("/tcp").put("applicationName","EV01B13_VAN").put("address","172.19.0.1").put("dbUser","postgres");
        cfg.withObject("/van/identity").put("database","van_sim");
        B13RecoveryLauncher.backend(jdbc,cfg);
        org.mockito.Mockito.verify(jdbc).queryForList(sql,"EV01B13_VAN");
        for(String address:java.util.List.of("172.19.0.9","172.19.0.1/32")) {
            org.mockito.Mockito.when(jdbc.queryForList(sql,"EV01B13_VAN")).thenReturn(java.util.List.of(
                java.util.Map.of("datname","van_sim","client_addr",address,"usename","postgres")));
            assertThatThrownBy(()->B13RecoveryLauncher.backend(jdbc,cfg)).hasMessageContaining("mismatched");
        }
    }
    @Test void jdbcServerAddressUsesHostInIdentityAndConnectionGuard() {
        assertThat(B13RecoveryLauncher.identitySql()).contains("host(inet_server_addr())").doesNotContain("inet_server_addr()::text");
        var cfg=B13RecoveryLauncher.JSON.createObjectNode();
        cfg.withObject("/identity").put("database","payment_sim").put("user","postgres").put("address","172.19.0.2").put("port",5432).put("directory","/data").put("system","123");
        assertThat(B13RecoveryLauncher.connectionGuard(cfg)).contains("host(inet_server_addr())='172.19.0.2'").doesNotContain("inet_server_addr()::text");
    }
}
