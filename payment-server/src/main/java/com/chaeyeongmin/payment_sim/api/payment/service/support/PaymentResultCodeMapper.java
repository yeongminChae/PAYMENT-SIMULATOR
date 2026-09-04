package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.ReversalResultStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

public final class PaymentResultCodeMapper {

    private PaymentResultCodeMapper() {
    }

    public static ResultCode fromFinalStatus(PaymentFinalStatus status) {
        return switch (status) {
            case APPROVED -> ResultCode.OK;
            case DECLINED -> ResultCode.DECLINED;
            case UNKNOWN_TIMEOUT -> ResultCode.UNKNOWN_TIMEOUT;
            case PROCESSING -> ResultCode.RETRY_LATER;
        };
    }

    public static ResultCode fromCancelResultStatus(CancelResultStatus status) {
        return switch (status) {
            // cancel inquiry에서 DB CANCELLED를 확인한 경우에도 API 결과 코드는 OK다.
            case CANCELLED -> ResultCode.OK;
            case ALREADY_CANCELLED -> ResultCode.ALREADY_CANCELLED;
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED;
            case CANCEL_NOT_ALLOWED -> ResultCode.CANCEL_NOT_ALLOWED;
            case RETRY_LATER -> ResultCode.RETRY_LATER;
        };
    }

    public static ResultCode fromReversalResultStatus(ReversalResultStatus status) {
        return switch (status) {
            case REVERSED -> ResultCode.OK;
            case ALREADY_REVERSED -> ResultCode.ALREADY_REVERSED;
            case REVERSAL_DECLINED -> ResultCode.REVERSAL_DECLINED;
            case REVERSAL_NOT_ALLOWED -> ResultCode.REVERSAL_NOT_ALLOWED;
            case RETRY_LATER -> ResultCode.RETRY_LATER;
        };
    }

    /**
     * 내부 취소 저장 상태를 공통 API 결과 코드로 변환한다.
     *
     * <p>
     * UNKNOWN_TIMEOUT은 외부 취소 처리 여부가 미확정인 상태이므로 클라이언트에는 재시도/후속조회 의미의
     * RETRY_LATER로 노출한다.
     */
    public static ResultCode fromCancelStatus(CancelStatus status) {
        return switch (status) {
            case CANCELLED -> ResultCode.OK;
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED;
            case PENDING,
                 UNKNOWN_TIMEOUT -> ResultCode.RETRY_LATER;
        };
    }

    public static String codeName(PaymentFinalStatus status) {
        return fromFinalStatus(status).name();
    }

    public static String codeName(CancelResultStatus status) {
        return fromCancelResultStatus(status).name();
    }

    public static String codeName(ReversalResultStatus status) {
        return fromReversalResultStatus(status).name();
    }

    /**
     * PAYMENT_CANCEL 내부 상태를 직접 result_code로 노출해야 하는 저수준 이벤트/로그용 매핑이다.
     * 일반 API 응답은 가능하면 CancelResponse.cancelStatus를 거쳐 fromCancelResultStatus를 사용한다.
     */
    public static String codeName(CancelStatus status) {
        return fromCancelStatus(status).name();
    }

}
