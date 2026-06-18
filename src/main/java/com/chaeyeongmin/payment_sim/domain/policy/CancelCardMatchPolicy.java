package com.chaeyeongmin.payment_sim.domain.policy;

import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 원승인 카드와 취소 요청 카드의 일치 여부를 판단하는 정책 골격.
 * <p>
 * TODO(v2.1.0): 다음 PR에서 PaymentCancelServiceImpl의 C4 승인 상태 검증 이후,
 * C5 PENDING row 생성 이전에 연결한다.
 *
 * <p>
 * 카드 불일치 시 후보 정책:
 * - ResultCode.CANCEL_NOT_ALLOWED
 * - message: CARD_MISMATCH
 */
@Component
public class CancelCardMatchPolicy {

    public boolean matches(
            PaymentAttempt originalAttempt,
            CardNumber cancelCard
    ) {
        if (originalAttempt == null || cancelCard == null) return false;

        return Objects.equals(originalAttempt.cardBin(), cancelCard.bin8()) &&
                Objects.equals(originalAttempt.cardLast4(), cancelCard.last4()
                );
    }

}
