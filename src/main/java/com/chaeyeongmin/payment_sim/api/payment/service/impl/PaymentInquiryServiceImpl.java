package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.api.payment.validate.InquiryRequestValidator;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentInquiryRepository;
 import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInquiryServiceImpl implements PaymentInquiryService {

    private final PaymentInquiryRepository repository;
    private final VanGateway vanGateway;
    private final InquiryRequestValidator validator;
    private final VanInquiryAssembler vanInquiryAssembler;

    @Override
    public InquiryResponse inquiry(InquiryRequest request) {
        // Q1: 조회 요청은 Controller에서 수신/로깅

        // Q2: 요청 유효성 검증
        validator.validate(request);

        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();

        // Q3: 조회 대상 attempt 조회
        Optional<PaymentAttempt> attemptOpt =
                repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

        // Q3-1: 대상 없음
        if (attemptOpt.isEmpty()) {
            log.info("[inquiry][Q3] attempt not found. posTrx={}, attemptSeq={}",
                    posTrx, attemptSeq);

            throw new BusinessException(
                    ResultCode.NOT_FOUND,
                    "조회 대상 결제 시도가 존재하지 않습니다."
            );

        }

        // Q3-2: 대상 존재
        PaymentAttempt attempt = attemptOpt.get();
        log.info("[inquiry][Q3] attempt found. posTrx={}, attemptSeq={}, finalStatus={}",
                posTrx, attemptSeq, attempt.finalStatus());

        // Q4 : 조회 가능한 상태인지 분기
        return getInquiryResponse(attempt, posTrx, attemptSeq);


    }

    private InquiryResponse getInquiryResponse(
            PaymentAttempt attempt,
            String posTrx,
            int attemptSeq
    ) {
        PaymentFinalStatus finalStatus = attempt.getFinalStatusEnum();
        String approvalNo = attempt.approvalNo();
        String declineCode = attempt.declineCode();
        CardSummary cardSummary = getCardSummary(attempt.cardBin(), attempt.cardLast4());

        // * APPROVED → 이미 확정됨 → Q9 DB 재응답
        // * DECLINED → 이미 확정됨 → Q9 DB 재응답
        // * UNKNOWN_TIMEOUT → 조회로 확정 시도 필요 → Q5로 진행
        // * finalStatus == null 또는 PROCESSING → 아직 처리중 → Q10 retryLater
        return switch (finalStatus) {
            case APPROVED -> InquiryResponse.approved(posTrx, attemptSeq, approvalNo, cardSummary);
            case DECLINED -> InquiryResponse.declined(posTrx, attemptSeq, declineCode, cardSummary);
            case PROCESSING -> InquiryResponse.retryLater(posTrx, attemptSeq, cardSummary);
            // UNKNOWN_TIMEOUT -> Q5
            case UNKNOWN_TIMEOUT -> resolveUnknownTimeout(posTrx, attemptSeq, cardSummary);
        };

    }

    private InquiryResponse resolveUnknownTimeout(
            String posTrx,
            int attemptSeq,
            CardSummary cardSummary
    ) {
        // Q5: VAN 조회 요청 구성
        VanInquiryRequest vanInquiryReq = vanInquiryAssembler.getVanInquiryRequest(
                posTrx,
                attemptSeq,
                cardSummary.cardLast4()
        );

        // Q5: VAN 조회 호출
        VanInquiryResponse vanInquiryRes = vanGateway.inquiry(vanInquiryReq);
        PaymentFinalStatus vanStatus = vanInquiryRes.finalStatus();
        String declineCode = toDeclineCode(vanInquiryRes.declineCode());

        // Q5a: VAN 조회 결과 승인 확정
        if (vanStatus.equals(PaymentFinalStatus.APPROVED)) {
            return InquiryResponse.approved(
                    posTrx,
                    attemptSeq,
                    vanInquiryRes.approvalNo(),
                    cardSummary
            );
        }

        // Q5b: VAN 조회 결과 거절 확정
        if (vanStatus.equals(PaymentFinalStatus.DECLINED)) {
            return InquiryResponse.declined(
                    posTrx,
                    attemptSeq,
                    declineCode,
                    cardSummary
            );
        }

        // Q5c/Q8: VAN 조회 결과도 여전히 미확정
        return InquiryResponse.unknownTimeout(
                posTrx,
                attemptSeq,
                declineCode,
                cardSummary
        );

    }

    private CardSummary getCardSummary(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

    private String toDeclineCode(VanDeclineCode declineCode) {
        if (declineCode == null) return null;
        return declineCode.code();
    }

}