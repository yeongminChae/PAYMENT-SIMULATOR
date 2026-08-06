package com.chaeyeongmin.payment_sim.domain.policy.cancel;

import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelCardVerificationPolicy {
    private final CardFingerprintPolicy cardFingerprintPolicy;

    /**
     * 취소 요청 PAN의 HMAC fingerprint를 생성해 원승인 attempt의 fingerprint와 비교한다.
     * 원승인 fingerprint가 없는 legacy 거래는 저장된 BIN8/last4로 fallback 비교한다.
     * PAN과 fingerprint 원문은 로그에 남기지 않는다.
     */
    public boolean matchesOriginalAttempt(
            PaymentAttempt originalAttempt,
            String cancelCardNo
    ) {
        String originalFingerprint = originalAttempt.cardFingerprint();

        if (originalFingerprint == null || originalFingerprint.isBlank()) {
            CardNumber cancelCardNumber = new CardNumber(cancelCardNo);

            return originalAttempt.cardBin() != null
                    && originalAttempt.cardBin().equals(cancelCardNumber.bin8())
                    && originalAttempt.cardLast4() != null
                    && originalAttempt.cardLast4().equals(cancelCardNumber.last4());
        }

        String cancelFingerprint = cardFingerprintPolicy.generate(cancelCardNo);

        return cardFingerprintPolicy.matchesFingerprint(
                cancelFingerprint,
                originalFingerprint
        );
    }

}
