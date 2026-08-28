package com.chaeyeongmin.van_sim.transaction.tcp.config;

import com.chaeyeongmin.van_sim.transaction.tcp.VanTcpMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.ip.dsl.Tcp;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.integration.ip.tcp.serializer.ByteArrayLengthHeaderSerializer;

/**
 * VAN 공용 TCP 서버의 연결, 프레이밍, 핸들러 라우팅을 구성한다.
 * <p>
 * 이 설정은 TCP 연결 계층만 담당하고, 전문 해석과 업무 처리는
 * {@link VanTcpMessageDispatcher} 이후의 업무별 핸들러에 위임한다.
 * <p>
 * Release 4부터는 같은 TCP port에서 APPROVAL과 INQUIRY를 함께 처리한다.
 * 실제 업무 구분은 inbound gateway가 아니라 {@link VanTcpMessageDispatcher}가 messageType으로 수행한다.
 */
@Configuration
@Profile("postgres")
public class VanTcpServerConfig {

    /**
     * VAN TCP 프로토콜의 4-byte Big Endian length header serializer/deserializer를 만든다.
     * <p>
     * Spring Integration의 기본 length header serializer를 사용하므로,
     * 직접 byte framing codec을 구현하지 않는다.
     */
    @Bean
    public ByteArrayLengthHeaderSerializer vanTcpLengthHeaderSerializer() {
        return new ByteArrayLengthHeaderSerializer(ByteArrayLengthHeaderSerializer.HEADER_SIZE_INT);
    }

    /**
     * VAN TCP 요청을 받을 server connection factory를 만든다.
     * <p>
     * 애플리케이션 시작 시 한 번 Bean으로 생성되고, Spring Integration lifecycle에 의해
     * 설정된 port를 listen한다. port가 0이면 테스트에서 OS가 random port를 할당한다.
     */
    @Bean
    public TcpNetServerConnectionFactory vanTcpServerConnectionFactory(
            @Value("${van.tcp.port}") int port,
            ByteArrayLengthHeaderSerializer vanTcpLengthHeaderSerializer
    ) {
        TcpNetServerConnectionFactory connectionFactory = new TcpNetServerConnectionFactory(port);

        // 요청 payload를 읽을 때와 응답 payload를 쓸 때 모두 동일한 4-byte length header를 사용한다.
        connectionFactory.setSerializer(vanTcpLengthHeaderSerializer);
        connectionFactory.setDeserializer(vanTcpLengthHeaderSerializer);
        return connectionFactory;
    }

    /**
     * TCP inbound gateway를 messageType dispatcher에 연결한다.
     * <p>
     * Spring Integration이 length header를 제거한 JSON payload byte[]를 전달하면,
     * dispatcher가 APPROVAL 또는 INQUIRY 핸들러의 응답 payload를 반환한다.
     * 이 메서드는 어떤 업무인지 직접 판단하지 않고, 단일 TCP 입구를 dispatcher에 연결하는 배선 역할만 한다.
     */
    @Bean
    public IntegrationFlow vanTcpInboundGateway(
            TcpNetServerConnectionFactory vanTcpServerConnectionFactory,
            VanTcpMessageDispatcher vanTcpMessageDispatcher
    ) {
        return IntegrationFlow.from(Tcp.inboundGateway(vanTcpServerConnectionFactory))
                // length header가 제거된 JSON payload를 messageType dispatcher에 전달한다.
                .handle(byte[].class, (payload, headers) -> vanTcpMessageDispatcher.dispatch(payload))
                .get();
    }
}
