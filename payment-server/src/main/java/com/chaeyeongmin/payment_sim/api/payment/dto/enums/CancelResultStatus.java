package com.chaeyeongmin.payment_sim.api.payment.dto.enums;

/**
 * 취소 API 응답 DTO({@code CancelResponse})의 cancelStatus 필드에 들어가는 상태 enum.
 *
 * <p>
 * CancelResponse가 응답 data 전체라면, CancelResultStatus는 그 data 안에서
 * "이번 취소 요청을 클라이언트에게 어떤 의미로 설명할지"를 나타내는 값 하나다.
 *
 * <p>
 * 이 enum은 DB 저장 상태가 아니다.
 * DB row의 상태는 {@code CancelStatus}가 표현하고, 이 enum은 API 응답 의미를 표현한다.
 * 예를 들어 DB row가 CANCELLED여도 기존 취소 row를 재응답하는 경우에는
 * API 의미상 ALREADY_CANCELLED를 내려줄 수 있다.
 *
 * <p>
 * {@code PaymentApiResponseMapper}는 이 값을 보고 공통 응답의 result_code를 결정한다.
 */
public enum CancelResultStatus {
    /**
     * 이번 요청으로 취소가 성공 확정됨.
     */
    CANCELLED,

    /**
     * 기존 취소 row가 이미 취소 완료 상태임.
     */
    ALREADY_CANCELLED,

    /**
     * 원거래 상태 등 정책상 취소할 수 없음.
     */
    CANCEL_NOT_ALLOWED,

    /**
     * 취소 요청은 접수됐지만 결과가 아직 미확정임.
     */
    RETRY_LATER,

    /**
     * VAN 또는 취소 정책에 의해 취소가 거절됨.
     */
    CANCEL_DECLINED
}
