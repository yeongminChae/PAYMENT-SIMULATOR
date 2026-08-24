package com.chaeyeongmin.payment_sim.van.client.tcp;

/**
 * VAN TCP transport client.
 * <p>
 * 이 인터페이스는 byte[] 송수신만 담당하며, 승인/거절 같은 업무 의미나 JSON 구조를 알지 않는다.
 */
public interface VanTcpClient {

    /**
     * TCP 서버로 request payload를 보내고 response payload를 받는다.
     */
    byte[] send(byte[] requestPayload);
}
