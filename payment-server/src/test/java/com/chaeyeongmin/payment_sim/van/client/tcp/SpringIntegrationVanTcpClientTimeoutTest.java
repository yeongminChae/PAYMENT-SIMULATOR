package com.chaeyeongmin.payment_sim.van.client.tcp;

import com.chaeyeongmin.payment_sim.van.client.tcp.config.VanTcpClientConfig;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = SpringIntegrationVanTcpClientTimeoutTest.TestConfig.class)
public class SpringIntegrationVanTcpClientTimeoutTest {

    private static ServerSocket serverSocket;
    private static Thread serverThread;

    @Autowired
    VanTcpClient vanTcpClient;

    /**
     * Spring test context가 뜨기 전에 테스트 전용 TCP 서버와 client 설정을 함께 준비한다.
     *
     * <p>
     * {@link DynamicPropertySource}는 application.yml 값보다 먼저 테스트용 property를 동적으로 등록한다.
     * 여기서는 ServerSocket(0)으로 OS가 비어 있는 port를 고르게 하고,
     * VanTcpClient가 실제 VAN Simulator 대신 그 local port로 접속하게 만든다.
     *
     * <p>
     * 이 테스트의 목적은 "연결 성공 후 응답이 오지 않는 상황"이므로,
     * 서버는 request framing만 읽고 response framing은 쓰지 않는다.
     * read-timeout-ms를 짧게 잡아 VanTcpClient가 timeout을 빠르게 감지하는지 확인한다.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        serverSocket = new ServerSocket(0);

        // VanTcpClientConfig가 읽는 설정값을 테스트 context에 주입한다.
        // random port를 쓰면 로컬 개발 환경/CI에서 고정 port 충돌 없이 테스트할 수 있다.
        registry.add("payment.van.mode", () -> "tcp");
        registry.add("payment.van.tcp.host", () -> "localhost");
        registry.add("payment.van.tcp.port", serverSocket::getLocalPort);

        // 테스트를 빨리 끝내기 위해 짧게.
        registry.add("payment.van.tcp.connect-timeout-ms", () -> 1000);
        registry.add("payment.van.tcp.read-timeout-ms", () -> 500);

        serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept();
                 DataInputStream input = new DataInputStream(socket.getInputStream())
            ) {

                // client가 보낸 [4-byte length][payload] 요청을 끝까지 읽어
                // "서버 연결/요청 송신은 성공했지만 응답만 없는 상황"을 만든다.
                int length = input.readInt();
                byte[] payload = new byte[length];
                input.readFully(payload);

                // 요청은 받았지만 response는 보내지 않는다.
                Thread.sleep(1500);

            }
            catch (Exception ignored) {}
        });

        serverThread.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.join(1000);
        }
    }

    @Test
    public void VAN_응답_timeout을_transport_timeout으로_변환한다() {
        byte[] request =
                "tcp-request".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> vanTcpClient.send(request))
                .isInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Configuration
    @EnableIntegration
    @Import(VanTcpClientConfig.class)
    static class TestConfig {
    }
}
