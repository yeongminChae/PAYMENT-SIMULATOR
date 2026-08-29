package com.chaeyeongmin.payment_sim.van.client.tcp;

import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpClientException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import org.springframework.integration.MessageTimeoutException;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.core.GenericMessagingTemplate;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Spring Integration TCP outbound gateway를 호출하는 transport 구현체다.
 * <p>
 * 업무 DTO, JSON 직렬화, 승인 상태 매핑은 알지 않고,
 * 이미 만들어진 request payload byte[]를 보내고 response payload byte[]만 반환한다.
 */
public class SpringIntegrationVanTcpClient implements VanTcpClient {

    private final MessageChannel outboundChannel;
    private final GenericMessagingTemplate messagingTemplate;

    public SpringIntegrationVanTcpClient(
            MessageChannel outboundChannel,
            long requestTimeoutMs
    ) {
        this.outboundChannel = outboundChannel;
        this.messagingTemplate = new GenericMessagingTemplate();
        this.messagingTemplate.setSendTimeout(requestTimeoutMs);
        this.messagingTemplate.setReceiveTimeout(requestTimeoutMs);
    }

    /**
     * outbound channel로 byte[] 요청 메시지를 보내고 TCP gateway 응답 메시지를 기다린다.
     * <p>
     * 실제 TCP framing은 channel 뒤에 연결된 Spring Integration outbound gateway와 connection factory가 처리한다.
     */
    @Override
    public byte[] send(byte[] requestPayload) {
        Message<byte[]> requestMessage = MessageBuilder.withPayload(requestPayload).build();
        Message<?> responseMessage;

        try {
            responseMessage = messagingTemplate.sendAndReceive(outboundChannel, requestMessage);

            if (responseMessage == null) {
                // 응답 대기 시간이 지나면 Payment 상위 계층이 통신 실패로 다룰 수 있도록 RuntimeException으로 올린다.
                throw new VanTcpResponseTimeoutException();
            }

            Object responsePayload = responseMessage.getPayload();
            if (responsePayload instanceof byte[] bytes) {
                return bytes;
            }

            // 이 transport의 계약은 byte[] 응답이다. 타입이 다르면 설정 또는 메시지 흐름 오류로 본다.
            throw new VanTcpClientException("VAN_TCP_RESPONSE_PAYLOAD_TYPE_INVALID");

        } catch (MessageTimeoutException e) {
            throw new VanTcpResponseTimeoutException(e);
        } catch (MessageHandlingException e) {
            if (isTcpConnectBeforeSendFailure(e)) {
                throw new VanTcpRequestNotSentException(e);
            }
            if (isTcpResponseReadTimeout(e)) {
                throw new VanTcpResponseTimeoutException(e);
            }
            throw e;
        }

    }

    /**
     * 실제 재현된 Spring Integration connection 생성 실패만 request-not-sent로 분류한다.
     *
     * <p>root cause가 ConnectException이라는 사실만으로는 부족하다. outbound gateway가
     * TcpNetClientConnectionFactory에서 새 socket을 만들다가 실패한 정확한 cause 구조와
     * stack frame을 모두 확인해 write/reset/EOF 등 전달 여부가 불확실한 실패를 제외한다.
     */
    private boolean isTcpConnectBeforeSendFailure(MessageHandlingException exception) {
        if (!(exception.getCause() instanceof UncheckedIOException uncheckedIOException)) {
            return false;
        }
        if (!(uncheckedIOException.getCause() instanceof ConnectException connectException)) {
            return false;
        }

        return hasFrame(
                uncheckedIOException,
                "org.springframework.integration.ip.tcp.connection.TcpNetClientConnectionFactory",
                "buildNewConnection"
        ) && hasFrame(
                connectException,
                "java.net.Socket",
                "connect"
        ) && hasFrame(
                connectException,
                "org.springframework.integration.ip.tcp.connection.TcpNetClientConnectionFactory",
                "createSocket"
        );
    }

    /**
     * Spring Integration이 socket read timeout을 MessageHandlingException으로 감싼 실제 경로다.
     * connect timeout이나 임의의 SocketTimeoutException과 섞지 않도록 TCP response deserialize
     * stack을 함께 확인하고 기존 response-timeout 정책으로 보낸다.
     */
    private boolean isTcpResponseReadTimeout(MessageHandlingException exception) {
        Throwable current = exception;
        while (current != null && current.getCause() != current) {
            if (current instanceof SocketTimeoutException) {
                return hasFrame(
                        current,
                        "org.springframework.integration.ip.tcp.serializer.ByteArrayLengthHeaderSerializer",
                        "deserialize"
                ) && hasFrame(
                        current,
                        "org.springframework.integration.ip.tcp.connection.TcpNetConnection",
                        "receiveAndProcessMessage"
                );
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasFrame(Throwable throwable, String className, String methodName) {
        for (StackTraceElement frame : throwable.getStackTrace()) {
            if (frame.getClassName().equals(className) && frame.getMethodName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

}
