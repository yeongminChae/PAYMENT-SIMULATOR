package com.chaeyeongmin.payment_sim.domain.policy.cancel;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelCardVerificationPolicy {
    private final CardFingerprintPolicy cardFingerprintPolicy;

    /**
     * 취소 요청 PAN의 HMAC fingerprint를 생성해 원승인 attempt의 fingerprint와 비교한다.
     * 원승인 fingerprint가 없는 legacy 거래는 저장된 BIN8/last4로 fallback 비교한다.
     * PAN과 fingerprint 원문은 로그에 남기지 않는다.
     */
    public void validateCardMatchesOriginalAttempt(
            PaymentAttempt originalAttempt,
            CancelRequest request
    ) {
        String cancelFingerprint = cardFingerprintPolicy.generate(request.cardNo());
        String originalFingerprint = originalAttempt.cardFingerprint();
        String cardBin = originalAttempt.cardBin();
        String cardLast4 = originalAttempt.cardLast4();

        if (originalFingerprint == null || originalFingerprint.isBlank()) {
            CardNumber cancelCardNumber = new CardNumber(request.cardNo());
            boolean legacyCardMatches =
                    cardBin != null && cardBin.equals(cancelCardNumber.bin8()) &&
                            cardLast4 != null && cardLast4.equals(cancelCardNumber.last4());

            if (legacyCardMatches) {
                log.warn("[cancel][C4-2] original attempt has no card fingerprint. legacy BIN8/last4 matched. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cardBin={}, cardLast4={}",
                        request.posTrx(),
                        request.originalPosTrx(),
                        request.originalAttemptSeq(),
                        cardBin,
                        cardLast4
                );

                return;
            }

            log.warn("[cancel][C4-2] original attempt has no card fingerprint. legacy BIN8/last4 mismatch. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cardBin={}, cardLast4={}",
                    request.posTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    cardBin,
                    cardLast4
            );

            throw new BusinessException(ResultCode.CANCEL_NOT_ALLOWED, "CARD_MISMATCH");
        }

        if (cardFingerprintPolicy.matchesFingerprint(
                cancelFingerprint,
                originalFingerprint
        ) == false) {
            log.warn("[cancel][C4-2] card fingerprint mismatch. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalFingerprintPresent={}, reason=CARD_MISMATCH",
                    request.posTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    originalFingerprint.isBlank() == false
            );

            throw new BusinessException(ResultCode.CANCEL_NOT_ALLOWED, "CARD_MISMATCH");
        }

    }

}
