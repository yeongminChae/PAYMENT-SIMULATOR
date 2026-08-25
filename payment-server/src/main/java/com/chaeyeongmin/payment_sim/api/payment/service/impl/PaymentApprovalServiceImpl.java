package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentApprovalTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.dto.*;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanApproveAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Service]
 * 결제 승인(Approve) 유스케이스의 “오케스트레이션(흐름 제어)”을 담당한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - 승인은 "외부 VAN 호출"이 포함되므로, 같은 posTrx에 대해 VAN을 중복 호출하지 않는 것이 중요하다.
 * - 그래서 먼저 DB에 attempt row를 만들고, 이후 VAN 결과를 조건부 update(FINAL_STATUS IS NULL)로 확정한다.
 * - 이미 확정된 attempt가 있으면 DB에 실제 저장된 값을 기준으로 재응답한다.
 * - 이번 구조에서는 DB lock/insert/update를 {@link PaymentApprovalTransactionService}로 분리했다.
 *   이 서비스는 A2 검증, A5/A6 VAN 호출, VAN 전후 이벤트 기록만 조립한다.
 * - 의도: 긴 외부 VAN 호출 시간을 DB 트랜잭션에 포함하지 않아서 row lock 점유 시간을 줄이고,
 *   승인 처리의 "DB 준비(TX1) -> 외부 호출(무 TX) -> DB 확정(TX2)" 경계를 코드에서 드러낸다.
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

    private final PaymentApprovalTransactionService transactionService;
    private final VanGateway vanGateway;
    private final VanApproveAssembler vanApproveAssembler;
    private final ApproveRequestValidator validator;
    private final PaymentEventLogRecorder paymentEventLogRecorder;

    @Override
    public ApproveResponse approve(ApproveRequest request) {

        // A2: 입력 검증.
        // - 유효하지 않은 승인 요청이면 DB row 생성이나 VAN 호출 전에 차단한다.
        // - 이후 TX1은 "검증이 끝난 승인 요청"만 받아 attempt 생성/재응답 여부를 판단한다.
        validator.validate(request);

        // TX1: 승인 준비 트랜잭션.
        // - posTrx row lock을 잡고 기존 attempt를 확인한다.
        // - 재응답 가능하면 existingResponse를 돌려주고, 신규 승인만 PROCESSING attempt를 만든다.
        // - 여기서 커밋된 뒤에만 외부 VAN 호출로 넘어가므로 lock을 잡은 채 네트워크 호출하지 않는다.
        PaymentApprovalPrepareResult prepared = transactionService.prepare(request);

        // A4 재응답: 기존 DB 결과 재사용이면 VAN 호출 없음.
        // - 승인 멱등성의 핵심 분기다. 같은 posTrx + 같은 payload는 DB 값 그대로 응답한다.
        // - prepared가 existing이면 cardIdentity가 없으므로 아래 A5/A6 경로로 내려가면 안 된다.
        if (prepared.isExisting()) {
            return prepared.existingResponse();
        }

        // A5: VAN 승인 요청 DTO 구성. (트랜잭션 없음)
        // - TX1에서 확정한 posTrx/attemptSeq/cardIdentity를 사용해 VAN 추적 가능한 요청을 만든다.
        // - PAN은 저장하지 않고, 여기서만 VAN 전송용으로 request에서 꺼내 assembler에 넘긴다.
        VanApproveRequest vanRequest =
                vanApproveAssembler.assemble(
                        prepared.posTrx(),
                        prepared.attemptSeq(),
                        request.getAmount(),
                        request.getCard().getPan(),
                        request.getCard().getExpiryYyMm(),
                        prepared.cardIdentity().cardBin(),
                        prepared.cardIdentity().cardLast4()
                );

        insertApproveEvent(
                PaymentEventType.APPROVE_VAN_REQUESTED,
                prepared.posTrx(),
                prepared.attemptSeq(),
                null,
                PaymentFinalStatus.PROCESSING.name(),
                null,
                null,
                null,
                "VAN approve requested"
        );

        // A6: 외부 VAN 승인 호출. (트랜잭션 없음)
        // - 네트워크 지연/타임아웃이 DB 트랜잭션 시간을 늘리지 않도록 TX1과 TX2 사이에서 수행한다.
        // - 동시성 제어는 TX1에서 만든 PROCESSING attempt와 이후 TX2 조건부 update가 담당한다.
        VanApproveResponse vanResponse = vanGateway.approve(vanRequest);

        insertApproveEvent(
                PaymentEventType.APPROVE_VAN_RESULT_RECEIVED,
                prepared.posTrx(),
                prepared.attemptSeq(),
                PaymentResultCodeMapper.codeName(vanResponse.finalStatus()),
                vanResponse.finalStatus().name(),
                vanResponse.vanTrxId(),
                vanResponse.approvalNo(),
                VanDeclineCodeMapper.toCode(vanResponse.declineCode()),
                "VAN approve result received"
        );

        // TX2: VAN 결과 확정 트랜잭션.
        // - FINAL_STATUS IS NULL 조건부 update로 최초 확정 요청만 저장한다.
        // - update miss가 나면 DB를 다시 읽어 저장된 값을 우선 응답한다.
        return transactionService.finalizeApproval(prepared, vanResponse);
    }

    /**
     * 승인 이벤트 로그를 구조화 컬럼만으로 저장한다.
     *
     * <p>
     * PAN/CVC/전문 원문은 파라미터에 포함하지 않는다.
     */
    private void insertApproveEvent(
            PaymentEventType eventType,
            String posTrx,
            int attemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        PaymentEventLogInsertParam event = PaymentEventLogInsertParam.approval(
                eventType,
                posTrx,
                attemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                note
        );

        if (eventType == PaymentEventType.APPROVE_CONFLICT) {
            // 충돌 이벤트는 이 메서드가 BusinessException으로 rollback된 뒤 listener가 기록한다.
            paymentEventLogRecorder.recordAfterRollback(event);
            return;
        }

        paymentEventLogRecorder.record(event);
    }

}
