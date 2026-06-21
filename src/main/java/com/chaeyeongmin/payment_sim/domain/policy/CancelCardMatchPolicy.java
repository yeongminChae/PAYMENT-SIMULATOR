package com.chaeyeongmin.payment_sim.domain.policy;

import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 원승인 카드와 취소 요청 카드의 일치 여부를 판단한다.
 * <p>
 * PAN 원문을 저장하거나 비교하지 않고, 원승인 attempt에 저장된 BIN 8자리와
 * 마지막 4자리가 모두 같은 경우에만 동일한 카드로 판단한다.
 */
@Component
public class CancelCardMatchPolicy {

    public boolean matchesOriginalCard(
            PaymentAttempt originalAttempt,
            CardNumber cancelCard
    ) {
        if (originalAttempt == null || cancelCard == null) return false;

        return Objects.equals(originalAttempt.cardBin(), cancelCard.bin8()) &&
                Objects.equals(originalAttempt.cardLast4(), cancelCard.last4()
                );
    }

}
