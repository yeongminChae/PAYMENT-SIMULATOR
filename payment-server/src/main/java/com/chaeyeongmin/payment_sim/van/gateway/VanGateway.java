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
     * [A6] VAN 승인 호출 (외부 승인 1회)
     * <p>
     * 입력:
     * - VanApproveRequest: posTrx/attemptSeq/amount + 민감정보(pan/expiry) 포함 (VAN 전송용)
     * <p>
     * 출력:
     * - VanApproveResponse: 승인 결과(승인번호/거절코드/타임아웃 여부 등) + VAN 응답 원천
     * <p>
     * 주의:
     * - 이 메서드는 "동기"로 동작한다(응답이 올 때까지 호출 스레드가 대기).
     * - 타임아웃/통신 실패는 예외로 던지고, 상위(Service)가 처리한다.
     */
    VanApproveResponse approve(VanApproveRequest request);

    /**
     * [Cancel] VAN 취소 호출
     * <p>
     * 입력:
     * - VanCancelRequest: 원거래 식별(posTrx/attemptSeq), 승인번호/취소금액 등
     * <p>
     * 출력:
     * - VanCancelResponse: 취소 승인번호/거절코드 등
     */
    VanCancelResponse cancel(VanCancelRequest request);

    /**
     * [Inquiry] VAN 승인 조회 호출
     * <p>
     * 사용 목적:
     * - 타임아웃/통신오류 등으로 "승인 결과가 미확정"일 때
     * VAN에 조회를 요청하여 최종 승인 여부를 확인한다.
     * <p>
     * 출력:
     * - VanInquiryResponse: 조회 결과(승인/거절/미확정)
     */
    VanInquiryResponse inquiry(VanInquiryRequest request);

}