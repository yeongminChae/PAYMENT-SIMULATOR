package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanApproveAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalServiceImpl implements PaymentApprovalService {

    private final PaymentAttemptRepository repository;
    private final VanGateway vanGateway;
    private final ApproveRequestValidator validator;
    private final VanApproveAssembler vanApproveAssembler;

    @Transactional
    @Override
    public ApproveResponse approve(ApproveRequest request) {

        // A2: 입력 검증(형식/필수/카드 기본 정책)
        validator.validate(request);

        String trx = request.getPosTrx();

        // A4: 중복/처리중/재시도 정책 분기
        Optional<PaymentAttempt> latestOpt = repository.findLatestByPosTrx(trx);

        // A4 : 중복/처리중 분기
        if (latestOpt.isPresent()) {
            PaymentAttempt latest = latestOpt.get();
            PaymentFinalStatus status = latest.getFinalStatusEnum();

            // DECLINED 이면 정책상 "새 attempt 허용" → 아래 A3로 진행
            if (status != PaymentFinalStatus.DECLINED) {
                // LOG#1: 중복/처리중으로 DB결과를 그대로 응답 (VAN 재호출 금지)
                log.info("[approve][A4] reuse db result. posTrx={}, attemptSeq={}, status={}",
                        trx, latest.attemptSeq(), status);

                // DECLINED 제외, 저장된 결과/처리중 상태를 그대로 응답한다.
                return getApproveResponse(
                        status,
                        trx,
                        latest.attemptSeq(),
                        latest.approvalNo(),
                        latest.declineCode(),
                        getCardSummary(latest.cardBin(), latest.cardLast4())
                );
            }

        }

        // A3: attemptSeq 발급 + attempt row INSERT (처리중 상태 생성)
        int attemptSeq = repository.insertAttemptSeq(trx);

        CardInput card = request.getCard();
        repository.insertAttempt(new AttemptInsertParam(
                trx,
                attemptSeq,
                request.getAmount(),
                card.bin8(),
                card.last4(),
                null,
                LocalDateTime.now()
        ));

        CardSummary summaryFromReq = getCardSummary(card.bin8(), card.last4());

        // A5: VAN 전송용 요청 DTO 구성
        VanApproveRequest vanApproveReq =
                vanApproveAssembler.getVanApproveRequest(trx, attemptSeq, request);

        // A6: VAN 승인 호출(외부 승인 1회)
        VanApproveResponse vanApproveRes = vanGateway.approve(vanApproveReq);

        // A7: VAN 결과 DB 확정 저장(멱등: FINAL_STATUS IS NULL)
        // - 이번 요청이 최초 확정 저장에 성공하면 RETURNING row로 결과를 받는다.
        AttemptResultUpdateParam updateParam =
                getAttemptResultUpdateParam(vanApproveRes, trx, attemptSeq);

        Optional<PaymentAttemptUpdatedRow> attemptUpdatedRowOpt =
                repository.updateAttemptResult(updateParam);

        // A8: 이번 호출이 확정 저장 “승자”면 RETURNING row 기반 응답 생성
        // - VAN 응답을 그대로 쓰기보다, DB에 "실제로 저장된 값"을 응답 소스로 사용한다.
        if (attemptUpdatedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow row = attemptUpdatedRowOpt.get();

            // LOG#2: 확정 저장 성공(승자)
            log.info("[approve][A7] finalized. posTrx={}, attemptSeq={}, finalStatus={}, vanTrxId={}",
                    trx, attemptSeq, row.finalStatus(), row.vanTrxId());

            return getApproveResponse(
                    row.finalStatus(),
                    trx,
                    attemptSeq,
                    row.approvalNo(),
                    row.declineCode(),
                    getCardSummary(row.cardBin(), row.cardLast4())
            );
        }

        // A7 저장 실패(0 rows): 누군가 먼저 확정했거나(경합), 아직 처리중일 수 있음
        // → 같은 attemptSeq 기준으로 재조회해서 A9/A10 분기
        Optional<PaymentAttempt> latestAttemptFromDb =
                repository.findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);

        if (latestAttemptFromDb.isPresent()) {
            PaymentAttempt row = latestAttemptFromDb.get();

            if (row.finalStatus() != null) {

                PaymentFinalStatus dbStatus = PaymentFinalStatus.valueOf(row.finalStatus());
                PaymentFinalStatus vanStatus = updateParam.finalStatus();

                // LOG#3: 경합으로 업데이트는 못했지만, DB에 확정값이 존재 → A9 재응답
                if (dbStatus.equals(vanStatus) == false) {
                    log.error("[approve][A7-0rows][MISMATCH] db finalStatus != van finalStatus. posTrx={}, attemptSeq={}, dbStatus={}, vanStatus={}, vanTrxId={}",
                            trx, attemptSeq, dbStatus, vanStatus, vanApproveRes.vanTrxId());
                }

                // A9: 이미 확정된 결과가 있으면 DB값 그대로 재응답
                return getApproveResponse(
                        dbStatus,
                        trx,
                        attemptSeq,
                        row.approvalNo(),
                        row.declineCode(),
                        getCardSummary(row.cardBin(), row.cardLast4())
                );
            }

            // A10: 정합성 이상/경합/DB 반영 실패 의심이면 retryLater(처리중) 응답
            log.error("[approve][A7-0rows][PROCESSING_AFTER_VAN] update miss; attempt still processing after VAN response. posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}",
                    trx, attemptSeq, vanApproveRes.finalStatus(), vanApproveRes.vanTrxId());

            return ApproveResponse.retryLater(trx, attemptSeq, summaryFromReq);
        }

        // row 자체가 없음(정상적으로는 거의 없어야 함)
        log.error("[approve][A7-0rows][CRITICAL_ATTEMPT_NOT_FOUND] update miss; attempt row not found after VAN response. posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}",
                trx, attemptSeq, vanApproveRes.finalStatus(), vanApproveRes.vanTrxId());

        // 정상 흐름이면 발생하면 안 되는 케이스(방어)
        // A10: 처리중이면 retryLater(처리중) 응답
        return ApproveResponse.unknownTimeout(
                trx,
                attemptSeq,
                "UNKNOWN_AFTER_UPDATE_MISS",
                summaryFromReq
        );

    }

    private AttemptResultUpdateParam getAttemptResultUpdateParam(
            VanApproveResponse vanApproveRes,
            String trx,
            int attemptSeq
    ) {
        String approvalNo = vanApproveRes.approvalNo();
        String vanTrxId = vanApproveRes.vanTrxId();
        String declineCode = toDeclineCode(vanApproveRes.declineCode());

        return switch (vanApproveRes.finalStatus()) {
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

    private ApproveResponse getApproveResponse(
            PaymentFinalStatus status,
            String trx,
            int attemptSeq,
            String approvalNo,
            String declineCode,
            CardSummary cardSummary
    ) {
        return switch (status) {
            case APPROVED -> ApproveResponse.approved(trx, attemptSeq, approvalNo, cardSummary);
            case DECLINED -> ApproveResponse.declined(trx, attemptSeq, declineCode, cardSummary);
            case UNKNOWN_TIMEOUT -> ApproveResponse.unknownTimeout(trx, attemptSeq, declineCode, cardSummary);
            case PROCESSING -> ApproveResponse.retryLater(trx, attemptSeq, cardSummary);
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