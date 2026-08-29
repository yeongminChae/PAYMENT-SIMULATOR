package com.chaeyeongmin.van_sim;

import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class VanSimulatorPostgresContextTest {

    @Test
    void contextLoadsWithPostgres() {
    }
}
