package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

public record VanInquiryTcpRequest(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq
) {
}
