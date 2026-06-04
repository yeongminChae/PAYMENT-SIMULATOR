package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;

/**
 * [Repository DTO] PAYMENT_ATTEMPT 확정 업데이트(UPDATE ... RETURNING) 결과 row
 *
 * 용도:
 * - A7 확정 저장에서 "실제로 DB에 반영된 최종 값"을 서비스로 반환하기 위한 DTO
 * - updateCount(1/0)만으로는 구분이 어려운 케이스를 서비스가 쉽게 후속처리하도록 돕는다.
 *
 * 비고:
 * - 대외 API DTO가 아니다.
 * - PAN/expiry 같은 민감정보는 포함하지 않는다.
 */
public record PaymentAttemptUpdatedRow(
        String posTrx,
        int attemptSeq,
        PaymentFinalStatus finalStatus,
        String approvalNo,
        String declineCode,
        String cardBin,
        String cardLast4,
        String vanTrxId
) {}