package com.chaeyeongmin.payment_sim.payment.application.inquiry.transaction;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentInquiryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryResponse;
import com.chaeyeongmin.payment_sim.payment.application.approval.AttemptResultUpdateParamFactory;
import com.chaeyeongmin.payment_sim.payment.application.card.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInquiryTransactionService {

    private final PaymentInquiryRepository paymentInquiryRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

    /**
     * VAN Inquiry 결과가 APPROVED/DECLINED로 확정된 뒤 UNKNOWN_TIMEOUT attempt를 DB에 최종 반영한다.
     * <p>
     * 응답은 VAN 응답 자체가 아니라 update RETURNING 또는 reread로 확인한 DB 저장값을 source of truth로 사용한다.
     */
    public InquiryResponse finalizeResolvedInquiry(
            String posTrx,
            int attemptSeq,
            VanInquiryResponse vanInquiryResponse,
            CardSummary fallbackCardSummary
    ) {
        AttemptResultUpdateParam param =
                AttemptResultUpdateParamFactory.fromVanInquiry(vanInquiryResponse, posTrx, attemptSeq);

        // Q6: UNKNOWN_TIMEOUT -> 최종 상태 조건부 update.
        // - updateUnknownToFinal은 "아직 UNKNOWN_TIMEOUT인 row"만 바꾸는 멱등성 보호 장치다.
        // - 다른 요청이 먼저 확정했으면 Optional.empty()가 될 수 있다.
        Optional<PaymentAttemptUpdatedRow> finalizedRowOpt =
                paymentInquiryRepository.updateUnknownToFinal(param);

        // Q7: Q6 저장 성공.
        // - VAN 응답을 바로 쓰지 않고, 실제 DB에 확정 저장된 값을 응답 소스로 사용한다.
        // - "응답으로 나간 값 = DB에 남은 값"을 맞추기 위한 규칙이다.
        if (finalizedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow finalizedRow = finalizedRowOpt.get();

            // CARD_BRAND도 update RETURNING row에 포함해 승인/조회 응답 카드 요약을 동일한 DB 저장값 기준으로 만든다.
            return getInquiryResponse(
                    finalizedRow.finalStatus(),
                    posTrx,
                    attemptSeq,
                    finalizedRow.approvalNo(),
                    finalizedRow.declineCode(),
                    CardSummaryFactory.fromStoredCard(
                            finalizedRow.cardBin(),
                            finalizedRow.cardLast4(),
                            finalizedRow.cardBrand()
                    )
            );

        }

        return handleUpdateUnknownToFinalMiss(
                posTrx,
                attemptSeq,
                param,
                vanInquiryResponse,
                fallbackCardSummary
        );
    }

    /**
     * UNKNOWN_TIMEOUT 확정 update가 0 rows였을 때의 방어/경합 처리.
     * <p>
     * 이 함수의 역할:
     * - 조건부 update 실패를 무조건 장애로 보지 않고, DB를 재조회해서 실제 상태를 확인한다.
     * - 결제 흐름에서는 동시 요청/재시도 때문에 "내 update가 실패했지만 다른 요청이 이미 확정한" 상황이 정상적으로 가능하다.
     * <p>
     * 응답 기준:
     * - 재조회 row가 있으면 DB 현재 상태를 기준으로 응답
     * - DB 상태와 방금 VAN 의도 상태가 다르면 로그로 정합성 확인 신호를 남김
     * - row 자체가 없으면 정상 흐름이 아니므로 UNKNOWN_TIMEOUT으로 방어 응답
     */
    private InquiryResponse handleUpdateUnknownToFinalMiss(
            String posTrx,
            int attemptSeq,
            AttemptResultUpdateParam resultUpdateParam,
            VanInquiryResponse vanInquiryResponse,
            CardSummary fallbackCardSummary
    ) {

        // Q6 update 0 rows 후 재조회.
        // - 승인 서비스의 A7 경합 처리와 동일하게 DB를 재조회하고, 실제 저장값을 응답 소스로 사용한다.
        Optional<PaymentAttempt> rereadOpt =
                paymentAttemptRepository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

        if (rereadOpt.isPresent()) {
            PaymentAttempt rereadAttempt = rereadOpt.get();
            PaymentFinalStatus dbStatus = rereadAttempt.getFinalStatusEnum();

            // VAN은 APPROVED/DECLINED를 의도했는데 DB가 다른 최종 상태라면 운영 확인이 필요하다.
            // 단, DB가 여전히 UNKNOWN_TIMEOUT이면 "아직 미확정 유지"로 볼 수 있어 별도 mismatch로 보지 않는다.
            if (dbStatus != resultUpdateParam.finalStatus()
                    && dbStatus != PaymentFinalStatus.UNKNOWN_TIMEOUT) {
                log.error("[inquiry][Q6-0rows][MISMATCH] db finalStatus != intended finalStatus. posTrx={}, attemptSeq={}, dbStatus={}, intendedFinalStatus={}, vanTrxId={}",
                        posTrx, attemptSeq, dbStatus, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());
            }

            // UNKNOWN_TIMEOUT 후속조회 이후 PROCESSING으로 보이는 건 일반적이지 않다.
            // FINAL_STATUS null이 다시 관측된 것이므로, DB 상태 전이/테스트 데이터를 확인해야 한다.
            if (dbStatus == PaymentFinalStatus.PROCESSING) {
                log.error("[inquiry][Q6-0rows][PROCESSING_AFTER_VAN] update miss; attempt is processing after VAN inquiry. posTrx={}, attemptSeq={}, intendedFinalStatus={}, vanTrxId={}",
                        posTrx, attemptSeq, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());
            }

            // 재조회한 DB 상태를 그대로 응답한다.
            // - 이 경로에서도 VAN 응답보다 DB에 실제 저장된 값을 우선한다.
            // - 카드 요약도 재조회한 PAYMENT_ATTEMPT의 cardBrand를 따라간다.
            return getInquiryResponse(
                    dbStatus,
                    posTrx,
                    attemptSeq,
                    rereadAttempt.approvalNo(),
                    rereadAttempt.declineCode(),
                    CardSummaryFactory.fromStoredCard(
                            rereadAttempt.cardBin(),
                            rereadAttempt.cardLast4(),
                            rereadAttempt.cardBrand()
                    )
            );

        }

        // row 자체가 사라진 경우.
        // - 조회 시작 시점에는 row가 있었으므로 정상적인 결제 흐름에서는 거의 없어야 한다.
        // - 클라이언트에게 잘못된 승인/거절을 단정하지 않고 UNKNOWN_TIMEOUT으로 방어한다.
        log.error("[inquiry][Q6-0rows][CRITICAL_ATTEMPT_NOT_FOUND] update miss; attempt row not found after VAN inquiry. posTrx={}, attemptSeq={}, intendedFinalStatus={}, vanTrxId={}",
                posTrx, attemptSeq, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());

        return InquiryResponse.unknownTimeout(
                posTrx,
                attemptSeq,
                VanDeclineCodeMapper.toCode(vanInquiryResponse.declineCode()),
                fallbackCardSummary
        );

    }

    /**
     * 상태와 필드 값을 InquiryResponse로 조립한다.
     * <p>
     * 이 함수는 "이미 상태가 결정된 뒤" 응답 DTO만 만드는 헬퍼다.
     * <p>
     * 분기 기준:
     * - APPROVED        : approvalNo 포함
     * - DECLINED        : declineCode 포함
     * - UNKNOWN_TIMEOUT : 아직 확정 불가, declineCode가 있으면 함께 반환
     * - PROCESSING      : FINAL_STATUS null에 대응하는 retryLater 응답
     */
    private InquiryResponse getInquiryResponse(
            PaymentFinalStatus status,
            String trx,
            int attemptSeq,
            String approvalNo,
            String declineCode,
            CardSummary cardSummary
    ) {
        return switch (status) {
            // Q9: 이미 확정된 건 DB 재응답
            case APPROVED -> InquiryResponse.approved(
                    trx,
                    attemptSeq,
                    approvalNo,
                    cardSummary
            );

            // Q9: 이미 확정된 건 DB 재응답
            case DECLINED -> InquiryResponse.declined(
                    trx,
                    attemptSeq,
                    declineCode,
                    cardSummary
            );

            case UNKNOWN_TIMEOUT -> InquiryResponse.unknownTimeout(
                    trx,
                    attemptSeq,
                    declineCode,
                    cardSummary
            );

            // Q10: 처리중 응답
            case PROCESSING -> InquiryResponse.retryLater(
                    trx,
                    attemptSeq,
                    cardSummary
            );

        };
    }
}
