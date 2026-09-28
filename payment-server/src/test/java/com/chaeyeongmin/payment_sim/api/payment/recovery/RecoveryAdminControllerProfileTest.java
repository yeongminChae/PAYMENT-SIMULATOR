package com.chaeyeongmin.payment_sim.api.payment.recovery;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin.RecoveryAdminCommandService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin.RecoveryAdminQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecoveryAdminControllerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RecoveryAdminQueryService.class, () -> mock(RecoveryAdminQueryService.class))
            .withBean(RecoveryAdminCommandService.class, () -> mock(RecoveryAdminCommandService.class))
            .withUserConfiguration(RecoveryAdminControllerConfig.class);

    @Test
    void postgres_profile이면_recovery_admin_controller가_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("postgres"))
                .run(context -> assertThat(context)
                        .hasSingleBean(RecoveryAdminController.class));
    }

    @Test
    void 기본_profile이면_recovery_admin_controller가_등록되지_않는다() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(RecoveryAdminController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RecoveryAdminController.class)
    static class RecoveryAdminControllerConfig {
    }
}
