package com.chaeyeongmin.payment_sim.van.client.tcp.config;

import com.chaeyeongmin.payment_sim.van.client.tcp.SpringIntegrationVanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.ip.dsl.Tcp;
import org.springframework.integration.ip.tcp.connection.TcpNetClientConnectionFactory;
import org.springframework.integration.ip.tcp.serializer.ByteArrayLengthHeaderSerializer;
import org.springframework.messaging.MessageChannel;

/**
 * Payment Server가 VAN Simulator에 요청을 보낼 TCP client transport를 구성한다.
 * <p>
 * 이 설정은 payment.van.mode=tcp일 때만 활성화된다.
 * 기존 Payment 업무 서비스는 이 설정을 직접 알지 않고, VanGateway 구현체를 통해 간접적으로 사용한다.
 */
@Configuration
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
public class VanTcpClientConfig {

    /**
     * VAN TCP 프로토콜의 length-prefixed framing을 처리하는 serializer/deserializer를 만든다.
     * <p>
     * HEADER_SIZE_INT는 4-byte header를 의미하고, Spring Integration이 Big Endian 길이값을 처리한다.
     */
    @Bean
    public ByteArrayLengthHeaderSerializer paymentVanTcpLengthHeaderSerializer() {
        return new ByteArrayLengthHeaderSerializer(ByteArrayLengthHeaderSerializer.HEADER_SIZE_INT);
    }

    /**
     * VAN Simulator로 접속할 TCP client connection factory를 만든다.
     * <p>
     * host/port/connect/read timeout은 application 설정 또는 환경변수로 바꿀 수 있다.
     * singleUse=true로 두어 승인 요청 1회마다 connection을 열고 응답 후 닫는 단순한 request-response 흐름을 사용한다.
     */
    @Bean
    public TcpNetClientConnectionFactory paymentVanTcpClientConnectionFactory(
            @Value("${payment.van.tcp.host}") String host,
            @Value("${payment.van.tcp.port}") int port,
            @Value("${payment.van.tcp.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${payment.van.tcp.read-timeout-ms}") int readTimeoutMs,
            ByteArrayLengthHeaderSerializer paymentVanTcpLengthHeaderSerializer
    ) {
        TcpNetClientConnectionFactory connectionFactory = new TcpNetClientConnectionFactory(host, port);

        // 요청 송신과 응답 수신 양쪽 모두 VAN Simulator와 같은 4-byte length header framing을 사용한다.
        connectionFactory.setSerializer(paymentVanTcpLengthHeaderSerializer);
        connectionFactory.setDeserializer(paymentVanTcpLengthHeaderSerializer);

        // connect timeout은 TCP 연결 수립 제한, soTimeout/remoteTimeout은 응답 대기 제한에 사용한다.
        connectionFactory.setConnectTimeout(connectTimeoutMs);
        connectionFactory.setSoTimeout(readTimeoutMs);
        connectionFactory.setSingleUse(true);
        return connectionFactory;
    }

    /**
     * VanTcpClient가 byte[] 요청 메시지를 흘려보낼 outbound channel이다.
     * <p>
     * 이 채널 뒤에 TCP outbound gateway가 붙어 실제 네트워크 송수신을 수행한다.
     */
    @Bean
    public MessageChannel paymentVanTcpOutboundChannel() {
        return new DirectChannel();
    }

    /**
     * outbound channel에 들어온 byte[] 메시지를 TCP outbound gateway로 라우팅한다.
     * <p>
     * Spring Integration이 여기서 connection factory의 serializer/deserializer를 사용해
     * length header를 붙여 보내고, 응답에서는 length header를 제거한 payload만 돌려준다.
     */
    @Bean
    public IntegrationFlow paymentVanTcpOutboundFlow(
            MessageChannel paymentVanTcpOutboundChannel,
            TcpNetClientConnectionFactory paymentVanTcpClientConnectionFactory,
            @Value("${payment.van.tcp.read-timeout-ms}") long readTimeoutMs
    ) {
        return IntegrationFlow.from(paymentVanTcpOutboundChannel)
                .handle(Tcp.outboundGateway(paymentVanTcpClientConnectionFactory)
                        .remoteTimeout(readTimeoutMs))
                .get();
    }

    /**
     * 업무 어댑터(TcpVanGateway)가 사용할 transport client Bean을 만든다.
     * <p>
     * 이 Bean은 byte[] 송수신만 제공하고, 승인 상태나 JSON 필드 의미는 해석하지 않는다.
     */
    @Bean
    public VanTcpClient paymentVanTcpClient(
            MessageChannel paymentVanTcpOutboundChannel,
            @Value("${payment.van.tcp.read-timeout-ms}") long readTimeoutMs
    ) {
        return new SpringIntegrationVanTcpClient(paymentVanTcpOutboundChannel, readTimeoutMs);
    }
}
