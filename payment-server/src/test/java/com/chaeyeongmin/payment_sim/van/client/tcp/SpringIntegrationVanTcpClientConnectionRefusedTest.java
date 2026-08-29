package com.chaeyeongmin.payment_sim.van.client.tcp;

import com.chaeyeongmin.payment_sim.van.client.tcp.config.VanTcpClientConfig;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringJUnitConfig(classes = SpringIntegrationVanTcpClientConnectionRefusedTest.TestConfig.class)
class SpringIntegrationVanTcpClientConnectionRefusedTest {

    private static final int CLOSED_PORT = findClosedLocalPort();

    @Autowired
    private VanTcpClient vanTcpClient;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.van.mode", () -> "tcp");
        registry.add("payment.van.tcp.host", () -> "127.0.0.1");
        registry.add("payment.van.tcp.port", () -> CLOSED_PORT);
        registry.add("payment.van.tcp.connect-timeout-ms", () -> 500);
        registry.add("payment.van.tcp.read-timeout-ms", () -> 1000);
    }

    @Test
    void 닫힌_port의_connect_before_send를_request_not_sent로_변환한다() {
        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> vanTcpClient.send(new byte[]{1})
        );

        assertThat(thrown).isInstanceOf(VanTcpRequestNotSentException.class);
        assertThat(rootCause(thrown)).isInstanceOf(java.net.ConnectException.class);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static int findClosedLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    @EnableIntegration
    @Import(VanTcpClientConfig.class)
    static class TestConfig {
    }
}
