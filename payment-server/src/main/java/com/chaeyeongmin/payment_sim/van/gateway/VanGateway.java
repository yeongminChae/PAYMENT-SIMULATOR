package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.van.client.dto.*;

/**
 * [Port / Client Interface] VanClient
 * <p>
 * 역할:
 * - A6 단계에서 "외부 VAN(또는 VAN 시뮬레이터)"과 통신하는 클라이언트 포트(인터페이스)다.
 * - Service는 HTTP/JSON/RestTemplate/WebClient 등 통신 구현 세부사항을 몰라도,
 * 이 인터페이스만 호출하여 VAN 요청/응답을 처리할 수 있다.
 * <p>
 * 설계 원칙:
 * - 동기 호출을 기본으로 한다(진도 우선).
 * -> 메서드는 "응답을 반환"하며, 호출부(Service)는 try/catch로 타임아웃/예외를 처리한다.
 * - DTO는 van.dto 패키지의 전용 모델만 사용한다.
 * -> API DTO(ApproveRequest)나 Domain(PaymentAttempt)을 VanClient가 알지 않게 하여 의존 방향을 깨끗하게 유지한다.
 * <p>
 * 예외/실패 처리 가이드(권장):
 * - 네트워크 오류/타임아웃/5xx 등 통신 실패는 RuntimeException(예: VanClientException)으로 던진다.
 * - Service는 이를 잡아서:
 * - PROCESSING 유지 + retryLater 반환
 * - 또는 정책에 따라 UNKNOWN_TIMEOUT 확정(A7)로 저장
 * 중 하나로 수렴시킨다.
 * <p>
 * 확장:
 * - 추후 비동기로 전환할 때도, 구현체에서 @Async/CompletableFuture/WebClient 등을 적용하고
 * 인터페이스는 그대로 유지하거나, 별도 AsyncVanClient를 추가하는 방식으로 확장할 수 있다.
 */
public interface VanGateway {

    /**
     * [Approve] VAN 승인 호출.
     *
     * <p>
     * Payment는 승인 준비 TX에서 attempt를 만든 뒤 이 포트를 호출한다.
     * 응답을 받으면 승인 확정 TX에서 DB 정본으로 반영한다.
     */
    VanApproveResponse approve(VanApproveRequest request);

    /**
     * [Cancel] VAN 취소 호출.
     *
     * <p>
     * Payment는 취소 준비 TX에서 원승인 검증과 PENDING row 생성을 끝낸 요청만 이 포트를 호출한다.
     * TCP 구현에서는 cancelPosTrx와 원승인 거래 식별 정보를 VAN Simulator에 전달한다.
     */
    VanCancelResponse cancel(VanCancelRequest request);

    /**
     * [Reversal] VAN reversal 호출.
     *
     * <p>
     * Release 5에서는 TCP VAN Simulator mode 전용 boundary로 사용한다.
     */
    VanReversalResponse reversal(VanReversalRequest request);

    /**
     * [Inquiry] VAN 승인 조회 호출.
     *
     * <p>
     * Payment는 승인 타임아웃/응답 유실로 결과가 미확정일 때 VAN에 조회를 요청한다.
     * 조회 결과는 기존 UNKNOWN_TIMEOUT attempt를 복구하는 데 사용한다.
     */
    VanInquiryResponse inquiry(VanInquiryRequest request);

}
