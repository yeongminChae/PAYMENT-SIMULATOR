package com.chaeyeongmin.payment_sim.van.client.tcp;

import com.chaeyeongmin.payment_sim.van.client.tcp.config.VanTcpClientConfig;
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
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = SpringIntegrationVanTcpClientTest.TestConfig.class)
class SpringIntegrationVanTcpClientTest {

    private static final byte[] RESPONSE_PAYLOAD = "tcp-response".getBytes(StandardCharsets.UTF_8);

    private static ServerSocket serverSocket;
    private static Thread serverThread;
    private static final AtomicInteger receivedLength = new AtomicInteger();
    private static final AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
    private static final CountDownLatch receivedLatch = new CountDownLatch(1);

    @Autowired
    private VanTcpClient vanTcpClient;

    /**
     * 테스트용 로컬 TCP 서버를 먼저 띄우고, client 설정이 해당 random port를 바라보게 한다.
     * <p>
     * VAN Simulator 애플리케이션을 띄우지 않고 transport framing만 독립적으로 검증하기 위한 fixture다.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        startLocalTcpServer();
        registry.add("payment.van.mode", () -> "tcp");
        registry.add("payment.van.tcp.host", () -> "localhost");
        registry.add("payment.van.tcp.port", () -> serverSocket.getLocalPort());
        registry.add("payment.van.tcp.connect-timeout-ms", () -> 1000);
        registry.add("payment.van.tcp.read-timeout-ms", () -> 3000);
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
    void TCP_client가_4byte_length_header로_request를_보내고_response를_수신한다() throws Exception {
        // given
        byte[] requestPayload = "tcp-request".getBytes(StandardCharsets.UTF_8);

        // when
        byte[] responsePayload = vanTcpClient.send(requestPayload);

        // then
        // 테스트 서버가 실제로 읽은 length 값과 payload를 확인해 4-byte Big Endian framing 송신을 검증한다.
        assertThat(receivedLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedLength.get()).isEqualTo(requestPayload.length);
        assertThat(receivedPayload.get()).isEqualTo(requestPayload);

        // 테스트 서버가 같은 framing으로 내려준 응답 payload를 client가 정상적으로 돌려주는지 확인한다.
        assertThat(responsePayload).isEqualTo(RESPONSE_PAYLOAD);
    }

    /**
     * 테스트용 VAN TCP 서버 역할을 한다.
     * <p>
     * 실제 프로토콜처럼 [4-byte length][payload]를 읽고,
     * 동일하게 [4-byte length][payload] 응답을 쓴다.
     */
    private static synchronized void startLocalTcpServer() throws Exception {
        if (serverSocket != null) {
            return;
        }

        serverSocket = new ServerSocket(0);
        serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept();
                 DataInputStream input = new DataInputStream(socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {

                // client가 보낸 4-byte Big Endian length header를 먼저 읽는다.
                int requestLength = input.readInt();
                byte[] requestPayload = new byte[requestLength];
                input.readFully(requestPayload);

                receivedLength.set(requestLength);
                receivedPayload.set(requestPayload);
                receivedLatch.countDown();

                // VAN Simulator 응답과 같은 방식으로 length header를 붙여 응답한다.
                output.writeInt(RESPONSE_PAYLOAD.length);
                output.write(RESPONSE_PAYLOAD);
                output.flush();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();
    }

    @Configuration
    @EnableIntegration
    @Import(VanTcpClientConfig.class)
    static class TestConfig {
    }
}
