package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.AttemptResultUpdateParamFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
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

    private final PaymentInquiryRepository repository;
    private final VanGateway vanGateway;
    private final InquiryRequestValidator validator;
    private final VanInquiryAssembler vanInquiryAssembler;

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
                repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

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
        CardSummary cardSummary =
                CardSummaryFactory.fromStoredCard(attempt.cardBin(), attempt.cardLast4());

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
        PaymentFinalStatus vanFinalStatus = vanInquiryResponse.finalStatus();
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
        // - 외부 응답을 바로 클라이언트에 내리지 않고, 먼저 DB의 UNKNOWN_TIMEOUT row를 최종 상태로 바꾼다.
        // - 그래야 다음 조회부터 DB에 실제 저장된 값만으로 같은 결과를 재응답할 수 있다.
        AttemptResultUpdateParam param =
                AttemptResultUpdateParamFactory.fromVanInquiry(vanInquiryResponse, posTrx, attemptSeq);

        // Q6: UNKNOWN_TIMEOUT -> 최종 상태 조건부 update.
        // - updateUnknownToFinal은 "아직 UNKNOWN_TIMEOUT인 row"만 바꾸는 멱등성 보호 장치다.
        // - 다른 요청이 먼저 확정했으면 Optional.empty()가 될 수 있다.
        Optional<PaymentAttemptUpdatedRow> finalizedRowOpt =
                repository.updateUnknownToFinal(param);

        // Q7: Q6 저장 성공.
        // - VAN 응답을 바로 쓰지 않고, 실제 DB에 확정 저장된 값을 응답 소스로 사용한다.
        // - "응답으로 나간 값 = DB에 남은 값"을 맞추기 위한 규칙이다.
        if (finalizedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow finalizedRow = finalizedRowOpt.get();

            return getInquiryResponse(
                    finalizedRow.finalStatus(),
                    posTrx,
                    attemptSeq,
                    finalizedRow.approvalNo(),
                    finalizedRow.declineCode(),
                    CardSummaryFactory.fromStoredCard(finalizedRow.cardBin(), finalizedRow.cardLast4())
            );

        }

        // Q6 update miss.
        // - 다른 요청이 먼저 상태를 바꿨거나, DB row 상태가 기대와 달라져 update 조건에 걸리지 않은 경우다.
        // - 이 처리는 별도 함수로 분리해 inquiry 본문에서 정상 흐름과 방어 흐름을 구분한다.
        return handleUpdateUnknownToFinalMiss(
                posTrx,
                attemptSeq,
                param,
                vanInquiryResponse,
                cardSummary
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
                repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);

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
            return getInquiryResponse(
                    dbStatus,
                    posTrx,
                    attemptSeq,
                    rereadAttempt.approvalNo(),
                    rereadAttempt.declineCode(),
                    CardSummaryFactory.fromStoredCard(rereadAttempt.cardBin(), rereadAttempt.cardLast4())
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
     * PaymentAttempt 전체를 받는 getInquiryResponse(...)와 달리,
     * update RETURNING row처럼 필요한 필드를 따로 들고 있는 경우에 사용한다.
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
