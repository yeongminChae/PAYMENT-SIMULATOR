package com.chaeyeongmin.payment_sim.payment.application.inquiry.service.impl;

import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryRequest;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryResponse;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.transaction.PaymentInquiryTransactionService;
import com.chaeyeongmin.payment_sim.payment.application.card.support.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryRequestValidator;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * [Service]
 * 결제 조회(Inquiry) 유스케이스의 흐름을 제어한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - inquiry는 단순히 DB row를 읽어 반환하는 API가 아니다.
 * - APPROVED / DECLINED처럼 이미 확정된 건은 DB 값을 그대로 재응답한다.
 * - PROCESSING은 아직 승인 처리 중인 상태라 VAN 조회 없이 retryLater 성격으로 응답한다.
 * - UNKNOWN_TIMEOUT만 VAN 후속조회 대상이다. 이때 VAN이 최종 결과를 알려주면 DB를 확정 update한다.
 * <p>
 * 즉, 조회 서비스는 "미확정 승인 건을 다시 확인해서 확정 가능한지 판단하는 유스케이스"까지 포함한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInquiryServiceImpl implements PaymentInquiryService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final VanGateway vanGateway;
    private final InquiryRequestValidator validator;
    private final VanInquiryAssembler vanInquiryAssembler;
    private final PaymentInquiryTransactionService transactionService;

    @Override
    public InquiryResponse inquiry(InquiryRequest request) {
        // Q1: 조회 요청은 Controller에서 수신/로깅한다.
        // - Service는 HTTP 세부 정보가 아니라 조회 유스케이스의 상태 판단에 집중한다.

        // Q2: 요청 유효성 검증.
        // - posTrx + attemptSeq는 조회 대상을 식별하는 복합 키 역할을 한다.
        // - 유효하지 않은 식별자로 DB/VAN 흐름에 들어가지 않도록 여기서 차단한다.
        validator.validate(request);

        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();

        // Q3: 조회 대상 attempt 조회.
        // - inquiry는 특정 결제 시도 1건을 대상으로 한다.
        // - posTrx만으로 조회하지 않는 이유는 DECLINED 이후 재승인처럼 같은 posTrx에 여러 attempt가 생길 수 있기 때문이다.
        Optional<PaymentAttempt> attemptOpt =
                paymentAttemptRepository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

        // Q3-1: 대상 없음.
        // - 조회 대상 자체가 없으므로 비즈니스 NOT_FOUND로 종료한다.
        // - 이 경우는 VAN에 물어볼 수 있는 내부 기준 row도 없기 때문에 외부 조회를 하지 않는다.
        if (attemptOpt.isEmpty()) {
            log.info("[inquiry][Q3] attempt not found. posTrx={}, attemptSeq={}",
                    posTrx, attemptSeq);

            throw new BusinessException(
                    ResultCode.NOT_FOUND,
                    "조회 대상 결제 시도가 존재하지 않습니다."
            );

        }

        // Q3-2: 대상 존재.
        // - 이후부터는 DB에 저장된 finalStatus를 기준으로 재응답/후속조회/처리중 응답을 결정한다.
        PaymentAttempt attempt = attemptOpt.get();
        log.info("[inquiry][Q3] attempt found. posTrx={}, attemptSeq={}, finalStatus={}",
                posTrx, attemptSeq, attempt.finalStatus());

        // Q4: attempt 상태별 조회 응답 분기.
        return getInquiryResponse(attempt, posTrx, attemptSeq);

    }

    /**
     * DB에서 조회한 PaymentAttempt를 기준으로 inquiry 결과를 결정한다.
     * <p>
     * 이 함수의 역할:
     * - DB 상태를 읽고 "즉시 재응답할지", "VAN 후속조회로 확정을 시도할지"를 판단한다.
     * - inquiry 메인 메서드가 대상 조회와 예외 처리에 집중하도록 상태 분기 로직을 분리한다.
     * <p>
     * 분기 기준:
     * - APPROVED / DECLINED: 이미 확정된 상태이므로 VAN 재조회 없이 DB 재응답
     * - PROCESSING: 아직 최초 승인 처리가 끝나지 않았으므로 retryLater
     * - UNKNOWN_TIMEOUT: VAN 후속조회로 최종 상태를 다시 확인
     */
    private InquiryResponse getInquiryResponse(
            PaymentAttempt attempt,
            String posTrx,
            int attemptSeq
    ) {
        PaymentFinalStatus attemptFinalStatus = attempt.getFinalStatusEnum();
        String approvalNo = attempt.approvalNo();
        String storedDeclineCode = attempt.declineCode();

        // 조회 응답의 카드 요약은 승인 당시 저장된 PAYMENT_ATTEMPT 값을 기준으로 만든다.
        // cardBrand까지 포함해야 승인 직후 응답과 사후 조회 응답의 카드 표시가 서로 어긋나지 않는다.
        CardSummary cardSummary =
                CardSummaryFactory.fromStoredCard(attempt.cardBin(), attempt.cardLast4(), attempt.cardBrand());

        return switch (attemptFinalStatus) {
            case APPROVED -> InquiryResponse.approved(
                    posTrx,
                    attemptSeq,
                    approvalNo,
                    cardSummary
            );

            case DECLINED -> InquiryResponse.declined(
                    posTrx,
                    attemptSeq,
                    storedDeclineCode,
                    cardSummary
            );

            case PROCESSING -> InquiryResponse.retryLater(
                    posTrx,
                    attemptSeq,
                    cardSummary
            );

            // Q5 대상.
            // - UNKNOWN_TIMEOUT은 "최종 결과를 모른다"는 확정 상태다.
            // - 후속조회로 VAN이 뒤늦게 APPROVED/DECLINED를 알려줄 수 있으므로 여기서만 외부 조회를 수행한다.
            case UNKNOWN_TIMEOUT -> resolveUnknownTimeout(
                    posTrx,
                    attemptSeq,
                    cardSummary,
                    attempt.vanTrxId()
            );

        };

    }

    /**
     * UNKNOWN_TIMEOUT 상태의 attempt를 VAN에 후속조회하고, 확정 가능하면 DB를 갱신한다.
     * <p>
     * 이 함수가 존재하는 이유:
     * - 조회 API의 복잡도는 대부분 이 분기에서 나온다.
     * - 단순 재응답과 달리 외부 VAN 조회, 조건부 DB update, update miss 후처리가 모두 필요하다.
     * <p>
     * 결과 정책:
     * - VAN도 UNKNOWN_TIMEOUT이면 DB 변경 없이 unknownTimeout 응답
     * - VAN이 APPROVED/DECLINED를 주면 UNKNOWN_TIMEOUT row를 최종 상태로 update
     * - update가 0 rows면 경합 가능성이 있으므로 DB를 다시 읽어 실제 저장 상태로 응답
     */
    private InquiryResponse resolveUnknownTimeout(
            String posTrx,
            int attemptSeq,
            CardSummary cardSummary,
            String vanTrxId
    ) {
        // Q5: VAN 조회 요청 DTO 구성.
        // - VAN은 posTrx/attemptSeq만이 아니라 기존 VAN 거래 추적키(vanTrxId)와 카드 last4를 함께 받을 수 있다.
        // - cardSummary는 DB에 저장된 최소 카드정보에서 온 값이라 민감정보 없이 후속조회에 사용할 수 있다.
        VanInquiryRequest vanInquiryRequest = vanInquiryAssembler.getVanInquiryRequest(
                posTrx,
                attemptSeq,
                cardSummary.cardLast4(),
                vanTrxId
        );

        // Q5-1: VAN 조회 호출.
        // - UNKNOWN_TIMEOUT 건에 대해서만 수행한다.
        // - 이미 APPROVED/DECLINED인 건은 DB에 실제 저장된 값을 기준으로 재응답한다.
        // - 불필요한 외부 VAN 조회를 줄이고, 저장된 상태와 응답이 어긋나는 상황을 막기 위해서다.
        VanInquiryResponse vanInquiryResponse = vanGateway.inquiry(vanInquiryRequest);
        PaymentFinalStatus vanFinalStatus = toPaymentFinalStatus(vanInquiryResponse);
        String responseDeclineCode = VanDeclineCodeMapper.toCode(vanInquiryResponse.declineCode());

        // Q5c/Q8: VAN 조회 결과도 여전히 미확정.
        // - 이 경우 DB 상태를 바꾸지 않는다. 이미 UNKNOWN_TIMEOUT으로 저장된 상태와 의미가 같기 때문이다.
        // - 응답은 후속조회 결과의 declineCode를 반영할 수 있지만, 최종 상태는 계속 UNKNOWN_TIMEOUT이다.
        if (vanFinalStatus.equals(PaymentFinalStatus.UNKNOWN_TIMEOUT)) {
            log.info("[inquiry][Q8] still unknown after VAN inquiry. posTrx={}, attemptSeq={}, vanTrxId={}",
                    posTrx, attemptSeq, vanInquiryResponse.vanTrxId());

            return InquiryResponse.unknownTimeout(
                    posTrx,
                    attemptSeq,
                    responseDeclineCode,
                    cardSummary
            );
        }

        // Q5a/Q5b: VAN이 APPROVED 또는 DECLINED로 확정 결과를 돌려준 경우.
        // - 외부 응답을 바로 클라이언트에 내리지 않고, DB의 UNKNOWN_TIMEOUT row 확정은 transaction service에 맡긴다.
        // - 그래야 다음 조회부터 DB에 실제 저장된 값만으로 같은 결과를 재응답할 수 있다.
        return transactionService.finalizeResolvedInquiry(
                posTrx,
                attemptSeq,
                vanInquiryResponse,
                cardSummary
        );

    }

    /** 승인 조회 결과만 Payment 승인 최종 상태로 해석하고 다른 조회 대상의 상태는 거부한다. */
    private PaymentFinalStatus toPaymentFinalStatus(VanInquiryResponse response) {
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND) {
            return PaymentFinalStatus.UNKNOWN_TIMEOUT;
        }

        if (response.status() == null) {
            throw new IllegalStateException("VAN inquiry SUCCESS response status is null");
        }

        return switch (response.status()) {
            case APPROVED -> PaymentFinalStatus.APPROVED;
            case DECLINED -> PaymentFinalStatus.DECLINED;
            case UNKNOWN -> PaymentFinalStatus.UNKNOWN_TIMEOUT;
            case CANCELLED,
                 CANCEL_DECLINED -> throw new IllegalStateException(
                    "Cancel inquiry status cannot be used in approval inquiry flow: " + response.status()
            );
            case REVERSED,
                 REVERSAL_DECLINED -> throw new IllegalStateException(
                    "Reversal inquiry status cannot be used in approval inquiry flow: " + response.status()
            );
        };
    }

}
