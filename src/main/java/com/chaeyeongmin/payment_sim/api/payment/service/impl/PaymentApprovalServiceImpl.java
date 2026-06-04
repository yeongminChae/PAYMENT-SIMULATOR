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
 * 이 클래스에서 기억할 핵심:
 * - 승인은 "외부 VAN 호출"이 포함되므로, 같은 posTrx에 대해 VAN을 중복 호출하지 않는 것이 중요하다.
 * - 그래서 먼저 DB에 attempt row를 만들고, 이후 VAN 결과를 조건부 update(FINAL_STATUS IS NULL)로 확정한다.
 * - 이미 확정된 attempt가 있으면 DB에 실제 저장된 값을 기준으로 재응답한다.
 * <p>
 * 상태 정책:
 * - FINAL_STATUS == NULL        : 아직 확정되지 않은 처리중(PROCESSING)
 * - FINAL_STATUS == APPROVED    : 승인 확정, approvalNo 존재
 * - FINAL_STATUS == DECLINED    : 승인 거절, 정책상 같은 posTrx로 새 attempt 허용
 * - FINAL_STATUS == UNKNOWN_TIMEOUT : VAN 응답/후속조회로도 확정하지 못한 미확정 종료 상태
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

        // A2: 입력 검증.
        // - 이 단계에서 실패하면 DB row를 만들거나 VAN을 호출하면 안 된다.
        // - 카드번호 검증은 저장 목적이 아니라 "승인 요청으로 받을 수 있는 입력인가"를 판단하기 위한 선행 방어선이다.
        validator.validate(request);

        String trx = request.getPosTrx();

        // A4: posTrx 기준 최신 attempt 조회.
        // - 동일 posTrx로 승인 요청이 다시 들어온 경우, 먼저 DB에 이미 처리 흔적이 있는지 확인한다.
        // - 이 조회 결과가 있으면 "신규 승인"이 아니라 "재요청/중복요청/이전 거절 후 재시도" 중 하나다.
        Optional<PaymentAttempt> latestOpt = repository.findLatestByPosTrx(trx);

        if (latestOpt.isPresent()) {
            PaymentAttempt latest = latestOpt.get();
            PaymentFinalStatus status = latest.getFinalStatusEnum();

            // A4 분기 기준:
            // - APPROVED / UNKNOWN_TIMEOUT / PROCESSING: 이미 진행 중이거나 결론이 난 요청이므로 VAN 재호출 금지.
            // - DECLINED: 승인 거절은 같은 posTrx로 다시 시도할 수 있게 열어둔 MVP 정책.
            //   따라서 DECLINED일 때만 아래 A3 신규 attempt 발급 흐름으로 내려간다.
            if (status != PaymentFinalStatus.DECLINED) {
                log.info("[approve][A4] reuse db result. posTrx={}, attemptSeq={}, status={}",
                        trx, latest.attemptSeq(), status);

                // DB 재응답.
                // - 저장된 attempt row를 기준으로 삼으므로, 응답도 DB 컬럼에서 조립한다.
                // - 처리중(PROCESSING)도 "아직 확정되지 않은 DB 상태"를 응답 DTO로 표현한 것이다.
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

        // A3: attemptSeq 발급.
        // - attemptSeq는 클라이언트가 보내는 값이 아니라 서버가 posTrx별로 발급하는 승인 시도 번호다.
        // - 같은 posTrx에서 여러 번 시도될 수 있으므로, DB 레벨 시퀀스/업서트로 중복을 막는다.
        int attemptSeq = repository.insertAttemptSeq(trx);

        CardInput card = request.getCard();

        // A3-1: PAYMENT_ATTEMPT row 생성.
        // - 이 row는 VAN 호출 전 "처리중 상태"를 남기는 기준점이다.
        // - FINAL_STATUS를 null로 저장해서 PROCESSING을 표현한다.
        // - PAN 원문은 저장하지 않고 BIN/last4 같은 최소 카드 정보만 저장한다.
        repository.insertAttempt(new AttemptInsertParam(
                trx,
                attemptSeq,
                request.getAmount(),
                card.bin8(),
                card.last4(),
                null,
                LocalDateTime.now()
        ));

        // 요청 카드정보에서 만든 응답용 카드 요약.
        // - 이후 update miss 등으로 DB row를 응답 소스로 쓰기 어려운 방어 분기에서 fallback으로 사용한다.
        // - 민감정보 없이 BIN/last4만 담기 때문에 로그/응답에 노출 가능한 최소 정보다.
        CardSummary summaryFromReq = getCardSummary(card.bin8(), card.last4());

        // A5: VAN 승인 요청 DTO 구성.
        // - 내부 API 요청(ApproveRequest)을 그대로 VAN에 넘기지 않고, VAN 계약에 맞는 DTO로 변환한다.
        // - assembler는 posTrx/attemptSeq/request를 합쳐 VAN이 추적 가능한 승인 전문을 만든다.
        VanApproveRequest vanApproveReq =
                vanApproveAssembler.getVanApproveRequest(trx, attemptSeq, request);

        // A6: VAN 승인 호출.
        // - 이 흐름에서 실제 외부 승인 시도는 여기서 1번만 발생해야 한다.
        // - 위 A4에서 재요청을 걸러낸 이유도 이 중복 호출을 막기 위해서다.
        VanApproveResponse vanApproveRes = vanGateway.approve(vanApproveReq);

        // A7: VAN 결과를 DB update 파라미터로 변환.
        // - VAN 응답 객체는 외부 시스템 관점의 결과다.
        // - AttemptResultUpdateParam은 PAYMENT_ATTEMPT 테이블에 저장할 내부 확정 결과다.
        // - 조건부 update(FINAL_STATUS IS NULL)에 사용되므로 멱등성의 핵심 파라미터다.
        AttemptResultUpdateParam updateParam =
                getAttemptResultUpdateParam(vanApproveRes, trx, attemptSeq);

        // A7-1: VAN 결과 DB 확정 저장.
        // - 이번 요청이 최초 확정 저장에 성공하면 RETURNING row로 결과를 받는다.
        // - 이미 다른 요청이 먼저 확정했거나 대상 row가 사라졌다면 Optional.empty()가 될 수 있다.
        // - 응답은 VAN 응답 원문보다 DB에 실제 저장된 값을 우선한다.
        Optional<PaymentAttemptUpdatedRow> attemptUpdatedRowOpt =
                repository.updateAttemptResult(updateParam);

        // A8: 이번 호출이 확정 저장 “승자”인 경우.
        // - 이번 요청이 최초 확정 저장에 성공하면 RETURNING row로 결과를 받는다.
        // - VAN 응답을 그대로 쓰기보다, DB에 "실제로 저장된 값"을 응답 소스로 사용한다.
        if (attemptUpdatedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow row = attemptUpdatedRowOpt.get();

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

        // A7 저장 실패(0 rows) 후처리.
        // - 정상 경합: 같은 attemptSeq를 다른 요청/스레드가 먼저 확정했을 수 있다.
        // - 비정상 가능성: VAN 응답은 받았는데 DB update가 반영되지 않아 row가 여전히 PROCESSING일 수 있다.
        // - 그래서 같은 posTrx + attemptSeq를 재조회해서 DB의 현재 상태를 다시 판단한다.
        Optional<PaymentAttempt> latestAttemptFromDb =
                repository.findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);

        if (latestAttemptFromDb.isPresent()) {
            PaymentAttempt row = latestAttemptFromDb.get();

            if (row.finalStatus() != null) {

                PaymentFinalStatus dbStatus = PaymentFinalStatus.valueOf(row.finalStatus());
                PaymentFinalStatus vanStatus = updateParam.finalStatus();

                // DB 확정값과 방금 받은 VAN 결과가 다르면 정합성 확인이 필요한 신호다.
                // 그래도 응답은 DB에 실제 저장된 상태를 기준으로 내린다.
                // 결제 시스템에서는 "실제로 저장된 상태"가 우선이다.
                if (dbStatus.equals(vanStatus) == false) {
                    log.error("[approve][A7-0rows][MISMATCH] db finalStatus != van finalStatus. posTrx={}, attemptSeq={}, dbStatus={}, vanStatus={}, vanTrxId={}",
                            trx, attemptSeq, dbStatus, vanStatus, vanApproveRes.vanTrxId());
                }

                // A9: 이미 확정된 결과가 있으면 DB값 그대로 재응답.
                return getApproveResponse(
                        dbStatus,
                        trx,
                        attemptSeq,
                        row.approvalNo(),
                        row.declineCode(),
                        getCardSummary(row.cardBin(), row.cardLast4())
                );
            }

            // A10: row는 있는데 FINAL_STATUS가 여전히 null인 경우.
            // - VAN 응답 이후에도 DB가 PROCESSING이면 정합성 이상 또는 update 실패 가능성이 있다.
            // - 클라이언트에게 확정 결과를 단정하지 않고 retryLater(PROCESSING)로 응답한다.
            log.error("[approve][A7-0rows][PROCESSING_AFTER_VAN] update miss; attempt still processing after VAN response. posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}",
                    trx, attemptSeq, vanApproveRes.finalStatus(), vanApproveRes.vanTrxId());

            return ApproveResponse.retryLater(trx, attemptSeq, summaryFromReq);
        }

        // row 자체가 없음.
        // - A3에서 insert한 attempt row를 A7 후처리에서 찾지 못한 상황이라 정상 흐름에서는 거의 없어야 한다.
        // - 이 경우에도 승인 성공/거절을 임의로 만들면 위험하므로 UNKNOWN_TIMEOUT 계열로 방어 응답한다.
        log.error("[approve][A7-0rows][CRITICAL_ATTEMPT_NOT_FOUND] update miss; attempt row not found after VAN response. posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}",
                trx, attemptSeq, vanApproveRes.finalStatus(), vanApproveRes.vanTrxId());

        return ApproveResponse.unknownTimeout(
                trx,
                attemptSeq,
                "UNKNOWN_AFTER_UPDATE_MISS",
                summaryFromReq
        );

    }

    /**
     * VAN 승인 응답을 PAYMENT_ATTEMPT update용 파라미터로 변환한다.
     * <p>
     * 왜 별도 함수인가?
     * - VAN 응답 DTO는 외부 시스템의 응답 형태이고, DB update 파라미터는 내부 저장 정책이다.
     * - 승인 성공/거절/타임아웃마다 저장해야 할 컬럼(approvalNo, declineCode, vanTrxId)이 다르다.
     * - 서비스 본문에서는 "VAN 결과를 DB 확정값으로 바꾼다"는 의도만 보이게 하기 위해 분리했다.
     */
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

    /**
     * PaymentFinalStatus를 승인 API 응답 DTO로 변환한다.
     * <p>
     * 이 함수의 역할:
     * - DB 재응답, VAN 처리 직후 응답, update miss 후 재조회 응답이 모두 같은 규칙을 쓰게 한다.
     * - 상태별 필수/선택 필드를 한 곳에서 맞춘다.
     * <p>
     * 분기 기준:
     * - APPROVED        : approvalNo를 포함한 승인 성공 응답
     * - DECLINED        : declineCode를 포함한 승인 거절 응답
     * - UNKNOWN_TIMEOUT : 확정 불가/타임아웃 응답
     * - PROCESSING      : 아직 확정 전이므로 retryLater 성격의 응답
     */
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

    /**
     * 저장/응답/로그에 사용할 수 있는 최소 카드 요약을 만든다.
     * <p>
     * PAN 전체나 만료일 같은 민감정보는 이 서비스 밖으로 들고 다니지 않는다.
     * 현재는 BIN/last4만 채우고, brand는 BIN 카탈로그 연동 후 채울 수 있도록 null로 둔다.
     */
    private CardSummary getCardSummary(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

    /**
     * VAN decline enum을 내부 저장/응답용 문자열 코드로 바꾼다.
     * <p>
     * declineCode는 승인 성공 시 null일 수 있으므로 null-safe 변환이 필요하다.
     */
    private String toDeclineCode(VanDeclineCode declineCode) {
        if (declineCode == null) return null;
        return declineCode.code();
    }

}
