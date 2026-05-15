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
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
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
        PaymentFinalStatus attemptFinalStatus = attempt.getFinalStatusEnum();
        String approvalNo = attempt.approvalNo();
        String storedDeclineCode = attempt.declineCode();
        CardSummary cardSummary =
                getCardSummary(attempt.cardBin(), attempt.cardLast4());

        // Inquiry 상태 분기 정책:
        // - APPROVED / DECLINED: 이미 확정된 상태이므로 Q9 DB 재응답
        // - PROCESSING: 아직 처리 중이므로 Q10 retryLater
        // - UNKNOWN_TIMEOUT: VAN 조회로 확정 여부를 확인해야 하므로 Q5 호출 대상
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

            // Q5: VAN 조회 요청
            case UNKNOWN_TIMEOUT -> resolveUnknownTimeout(
                    posTrx,
                    attemptSeq,
                    cardSummary
            );

        };

    }

    private InquiryResponse resolveUnknownTimeout(
            String posTrx,
            int attemptSeq,
            CardSummary cardSummary
    ) {
        // Q5: VAN 조회 요청 DTO 구성
        VanInquiryRequest vanInquiryRequest = vanInquiryAssembler.getVanInquiryRequest(
                posTrx,
                attemptSeq,
                cardSummary.cardLast4()
        );

        // Q5: VAN 조회 호출
        VanInquiryResponse vanInquiryResponse = vanGateway.inquiry(vanInquiryRequest);
        PaymentFinalStatus vanFinalStatus = vanInquiryResponse.finalStatus();
        String responseDeclineCode = toDeclineCode(vanInquiryResponse.declineCode());

        // Q5c/Q8: VAN 조회 결과도 여전히 미확정
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

        // Q5a/Q5b VAN 결과가 APPROVED 또는 DECLINED이면
        // Q6: DB updateUnknownToFinal 호출
        AttemptResultUpdateParam param =
                getAttemptResultUpdateParam(vanInquiryResponse, posTrx, attemptSeq);

        Optional<PaymentAttemptUpdatedRow> finalizedRowOpt  =
                repository.updateUnknownToFinal(param);

        if (finalizedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow finalizedRow = finalizedRowOpt.get();

            return getInquiryResponse(
                    finalizedRow.finalStatus(),
                    posTrx,
                    attemptSeq,
                    finalizedRow.approvalNo(),
                    finalizedRow.declineCode(),
                    getCardSummary(finalizedRow.cardBin(), finalizedRow.cardLast4())
            );

        }

        // 임시 방어: DB 저장에 성공하지 못했으므로 기존 UNKNOWN_TIMEOUT 응답
        return handleUpdateUnknownToFinalMiss(
                posTrx,
                attemptSeq,
                param,
                vanInquiryResponse,
                cardSummary
        );

    }

    private InquiryResponse handleUpdateUnknownToFinalMiss(
            String posTrx,
            int attemptSeq,
            AttemptResultUpdateParam resultUpdateParam,
            VanInquiryResponse vanInquiryResponse,
            CardSummary fallbackCardSummary
    ) {

        Optional<PaymentAttempt> rereadOpt =
                repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

        if (rereadOpt.isPresent()) {
            PaymentAttempt rereadAttempt = rereadOpt.get();
            PaymentFinalStatus dbStatus = rereadAttempt.getFinalStatusEnum();

            if (dbStatus != resultUpdateParam.finalStatus()
                    && dbStatus != PaymentFinalStatus.UNKNOWN_TIMEOUT) {
                log.error("[inquiry][Q6-0rows][MISMATCH] db finalStatus != intended finalStatus. posTrx={}, attemptSeq={}, dbStatus={}, intendedFinalStatus={}, vanTrxId={}",
                        posTrx, attemptSeq, dbStatus, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());
            }

            if (dbStatus == PaymentFinalStatus.PROCESSING) {
                log.error("[inquiry][Q6-0rows][PROCESSING_AFTER_VAN] update miss; attempt is processing after VAN inquiry. posTrx={}, attemptSeq={}, intendedFinalStatus={}, vanTrxId={}",
                        posTrx, attemptSeq, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());
            }

            return getInquiryResponse(
                    dbStatus,
                    posTrx,
                    attemptSeq,
                    rereadAttempt.approvalNo(),
                    rereadAttempt.declineCode(),
                    getCardSummary(rereadAttempt.cardBin(), rereadAttempt.cardLast4())
            );

        }

        log.error("[inquiry][Q6-0rows][CRITICAL_ATTEMPT_NOT_FOUND] update miss; attempt row not found after VAN inquiry. posTrx={}, attemptSeq={}, intendedFinalStatus={}, vanTrxId={}",
                posTrx, attemptSeq, resultUpdateParam.finalStatus(), vanInquiryResponse.vanTrxId());

        return InquiryResponse.unknownTimeout(
                posTrx,
                attemptSeq,
                toDeclineCode(vanInquiryResponse.declineCode()),
                fallbackCardSummary
        );

    }

    private AttemptResultUpdateParam getAttemptResultUpdateParam(
            VanInquiryResponse vanInquiryResp,
            String trx,
            int attemptSeq
    ) {
        String approvalNo = vanInquiryResp.approvalNo();
        String vanTrxId = vanInquiryResp.vanTrxId();
        String declineCode = toDeclineCode(vanInquiryResp.declineCode());

        return switch (vanInquiryResp.finalStatus()) {
            case APPROVED -> AttemptResultUpdateParam.approved(
                    trx,
                    attemptSeq,
                    approvalNo,
                    vanTrxId
            );

            case DECLINED -> AttemptResultUpdateParam.declined(
                    trx,
                    attemptSeq,
                    declineCode,
                    vanTrxId
            );

            case UNKNOWN_TIMEOUT,
                 PROCESSING -> AttemptResultUpdateParam.unknownTimeout(
                    trx,
                    attemptSeq,
                    declineCode,
                    vanTrxId
            );

        };

    }

    private InquiryResponse getInquiryResponse(
            PaymentFinalStatus status,
            String trx,
            int attemptSeq,
            String approvalNo,
            String declineCode,
            CardSummary cardSummary
    ) {
        return switch (status) {
            case APPROVED -> InquiryResponse.approved(
                    trx,
                    attemptSeq,
                    approvalNo,
                    cardSummary
            );

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

            case PROCESSING -> InquiryResponse.retryLater(
                    trx,
                    attemptSeq,
                    cardSummary
            );

        };
    }

    private CardSummary getCardSummary(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

    private String toDeclineCode(VanDeclineCode declineCode) {
        if (declineCode == null) return null;
        return declineCode.code();
    }

}
