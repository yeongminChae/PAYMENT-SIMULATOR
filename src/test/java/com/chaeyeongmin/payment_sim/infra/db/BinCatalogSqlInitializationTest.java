package com.chaeyeongmin.payment_sim.infra.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./build/bin-catalog-sql-init-test.db")
class BinCatalogSqlInitializationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sqlInitialization_shouldCreateBinCatalogAndSeedTestDataIdempotently() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT BIN, BRAND, ISSUER, COUNTRY, BIN_LEN, ACTIVE_YN
                FROM BIN_CATALOG
                ORDER BY BIN
                """);

        assertEquals(15, rows.size());

        assertRow(rows.get(0), "37111111", "AMEX", "AMEX_TEST", "US", 8, "Y");
        assertRow(rows.get(1), "41111111", "VISA", "KB_CARD_TEST", "KR", 8, "Y");
        assertRow(rows.get(4), "44444444", "VISA", "LOTTE_CARD_TEST", "KR", 8, "Y");
        assertRow(rows.get(5), "45555555", "VISA", "WOORI_CARD_TEST", "KR", 8, "Y");
        assertRow(rows.get(6), "49999999", "VISA", "INACTIVE_CARD_TEST", "KR", 8, "N");
        assertRow(rows.get(14), "63333333", "LOCAL", "HANA_CARD_TEST", "KR", 8, "Y");
    }

    private void assertRow(
            Map<String, Object> row,
            String bin,
            String brand,
            String issuer,
            String country,
            int binLen,
            String activeYn
    ) {
        assertEquals(bin, row.get("BIN"));
        assertEquals(brand, row.get("BRAND"));
        assertEquals(issuer, row.get("ISSUER"));
        assertEquals(country, row.get("COUNTRY"));
        assertEquals(binLen, ((Number) row.get("BIN_LEN")).intValue());
        assertEquals(activeYn, row.get("ACTIVE_YN"));
    }
}
