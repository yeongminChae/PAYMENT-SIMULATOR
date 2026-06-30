package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
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
            case CANCELLED -> ResultCode.OK;
            case ALREADY_CANCELLED -> ResultCode.ALREADY_CANCELLED;
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED;
            case CANCEL_NOT_ALLOWED -> ResultCode.CANCEL_NOT_ALLOWED;
            case RETRY_LATER -> ResultCode.RETRY_LATER;
        };
    }

    public static ResultCode fromCancelStatus(CancelStatus status) {
        return switch (status) {
            case CANCELLED -> ResultCode.OK;
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED;
            case PENDING -> ResultCode.RETRY_LATER;
        };
    }

    public static String codeName(PaymentFinalStatus status) {
        return fromFinalStatus(status).name();
    }

    public static String codeName(CancelResultStatus status) {
        return fromCancelResultStatus(status).name();
    }

    public static String codeName(CancelStatus status) {
        return fromCancelStatus(status).name();
    }

}
