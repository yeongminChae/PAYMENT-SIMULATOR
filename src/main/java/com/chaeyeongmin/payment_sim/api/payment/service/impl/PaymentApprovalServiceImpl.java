package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * [Service]
 * 결제 승인(Approve) 유스케이스의 “오케스트레이션(흐름 제어)”을 담당한다.
 * <p>
 * 책임(Responsibility)
 * - (IMP-A2) 승인 요청의 입력 유효성 검증을 1곳으로 묶어 수행한다.
 * - (IMP-A3) attempt_seq(승인 시도 번호)를 서버에서 발급/확보하고 DB에 기록한다.
 * - (향후) 중복/처리중 분기, VAN 호출, 타임아웃 후속조회, 최종결과 저장/재응답 등을 순서대로 연결한다.
 * <p>
 * 비고(현재 단계)
 * - MVP 단계에서는 validate() 통과 시 attempt 발급/저장까지 먼저 완성한다.
 * - INVALID는 “DB/VAN을 타지 않고” 즉시 종료되어야 하므로 validate()에서 예외로 끊는 방식을 권장한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentApprovalServiceImpl implements PaymentApprovalService {

    private final PaymentAttemptRepository repository;
    private final ApproveRequestValidator validator;

    @Override
    public ApproveResponse approve(ApproveRequest request) {

        // A2: 요청 유효성 검증 (실패 시 예외)
        validator.validate(request);

        String trx = request.getPosTrx();

        // 현재 저장된 결과 존재 여부 확보
        Optional<PaymentAttempt> latestOpt = repository.findLatestByPosTrx(trx);

        // A4 : 중복/처리중 분기
        if (latestOpt.isPresent()) {
            PaymentAttempt latestPaymentAttempt = latestOpt.get();

            int attempt = latestPaymentAttempt.attempt();
            String cardBin = latestPaymentAttempt.cardBin();
            String cardLast4 = latestPaymentAttempt.cardLast4();
            CardSummary cardSummary = getCardSummary(cardBin,cardLast4);

            return latestPaymentAttempt.finalStatus() != null
                    // 1. **이미 확정 결과가 저장되어 있음**
                    // - (APPROVED / DECLINED / UNKNOWN_TIMEOUT 중 하나가 DB에 존재)
                    ? getApproveResponse(latestPaymentAttempt, trx, attempt, cardSummary)
                    // 2. **처리 중(확정 결과가 아직 없음)**
                    // - DB에 attempt는 있으나 결과 컬럼이 아직 확정되지 않음
                    : ApproveResponse.processing(trx, attempt, cardSummary);

            // 그 외 -> A5로 이동
        }

        // A3. 결제 시도(attempt) 레코드 확보(DB 1차 방어)
        // A3: attempt 확보
        int attemptSeq = repository.insertAttemptSeq(trx);

        // A3: attempt 저장
        CardInput card = request.getCard();
        repository.insertAttempt(new AttemptInsertParam(
                trx,
                attemptSeq,
                request.getAmount(),
                card.bin8(),
                card.last4(),
                null, // TODO : [20260206] 카드브랜드 우선은 null로
                LocalDateTime.now()
        ));

        CardSummary summaryFromReq = getCardSummary(card.bin8(), card.last4());

        /*
         * [2026-02-08] A3 이후 즉시 RETRY_LATER를 반환하는 이유
         * - 현재 단계(MVP)에서는 A5(VAN 호출) ~ A7(최종결과 저장)가 아직 구현되지 않았다.
         * - 따라서 신규 attempt는 FINAL_STATUS=NULL(처리중) 상태로 저장만 하고,
         *   클라이언트에는 "처리중이니 재시도하라(RETRY_LATER)"를 명확히 응답한다.
         * - 후속 PR에서 A5~A7이 붙으면, 여기서 VAN 호출 후 결과 확정 응답으로 확장된다.
         */
        return ApproveResponse.retryLater(trx, attemptSeq, summaryFromReq);
    }

    private static ApproveResponse getApproveResponse(PaymentAttempt latestPaymentAttempt, String trx, int attempt, CardSummary cardSummary) {
        String approvalNo = latestPaymentAttempt.approvalNo();
        String declineCode = latestPaymentAttempt.declineCode();

        return switch (latestPaymentAttempt.getFinalStatusEnum()) {
            case APPROVED -> ApproveResponse.approved(trx, attempt, approvalNo, cardSummary);
            case DECLINED ->
                // TODO 20260208 declineCode 구현X
                    ApproveResponse.declined(trx, attempt, declineCode, cardSummary);
            case UNKNOWN_TIMEOUT ->
                    ApproveResponse.unknownTimeout(trx, attempt, declineCode, cardSummary);
            case PROCESSING -> ApproveResponse.retryLater(trx, attempt, cardSummary);
        };
    }

    private CardSummary getCardSummary(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

}