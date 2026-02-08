package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /*
     * A3의 “attemptSeq 발급 + insertAttempt” 트랜잭션 경계
     * 현재 A3에서 insertAttemptSeq()로 시퀀스 증가 후 insertAttempt() 저장이 이어지는데,
     * 두 쿼리가 같은 트랜잭션이 아니면 중간 실패 시
     * “시퀀스만 증가하고 attempt row는 없는 상태”가 생길 수 있어 @Transactional 추가
     */
    @Transactional
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

            int attemptSeq = latestPaymentAttempt.attemptSeq();
            CardSummary cardSummary = getCardSummary(
                    latestPaymentAttempt.cardBin(),
                    latestPaymentAttempt.cardLast4()
            );

            PaymentFinalStatus status = latestPaymentAttempt.getFinalStatusEnum();

            return switch (status) {
                case APPROVED ->
                        ApproveResponse.approved(trx, attemptSeq, latestPaymentAttempt.approvalNo(), cardSummary);

                case DECLINED ->
                        ApproveResponse.declined(trx, attemptSeq, latestPaymentAttempt.declineCode(), cardSummary);

                case UNKNOWN_TIMEOUT ->
                        ApproveResponse.unknownTimeout(trx, attemptSeq, latestPaymentAttempt.declineCode(), cardSummary);

                case PROCESSING ->
                    // A10: 처리중 → retryLater(=processing)로 응답
                        ApproveResponse.retryLater(trx, attemptSeq, cardSummary);
            };
        }

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

    private CardSummary getCardSummary(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

}