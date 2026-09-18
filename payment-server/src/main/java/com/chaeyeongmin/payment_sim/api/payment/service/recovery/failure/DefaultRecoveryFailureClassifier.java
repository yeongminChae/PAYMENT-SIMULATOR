package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import org.springframework.stereotype.Component;

/** Recovery Handler에서 알려진 통신 실패와 데이터 불일치를 기본 정책으로 분류한다. */
@Component
public class DefaultRecoveryFailureClassifier implements RecoveryFailureClassifier {

    @Override
    public RecoveryFailureType classify(Throwable throwable) {

        // 요청 미전송과 응답 시간 초과는 일시적인 통신 문제일 수 있으므로 다시 시도한다.
        if (throwable instanceof VanGatewayRequestNotSentException || throwable instanceof VanGatewayTimeoutException) {
            return RecoveryFailureType.RETRYABLE;
        }

        // 거래 식별자나 응답 규칙이 어긋난 경우에는 자동 판단을 멈춘다.
        if (throwable instanceof RecoveryInvariantViolationException) {
            return RecoveryFailureType.MANUAL_REVIEW;
        }

        // TCP 전문 오류를 포함해 정책에 없는 예외는 숨기지 않고 호출자에게 전파한다.
        return RecoveryFailureType.UNKNOWN;
    }
}
