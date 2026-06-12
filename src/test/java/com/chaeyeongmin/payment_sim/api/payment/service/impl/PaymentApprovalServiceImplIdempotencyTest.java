package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanApproveAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentApprovalServiceImplIdempotencyTest {

    private PaymentApprovalService service;
    private PaymentAttemptRepository repository;
    private VanGateway vanGateway;
    private ApproveRequestValidator validator;
    private VanApproveAssembler vanApproveAssembler;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        vanGateway = mock(VanGateway.class);
        validator = mock(ApproveRequestValidator.class);
        vanApproveAssembler = mock(VanApproveAssembler.class);

        service = new PaymentApprovalServiceImpl(
                repository,
                vanGateway,
                validator,
                vanApproveAssembler
        );
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-001
     * 기존 APPROVED attempt와 같은 payload로 승인 재요청하면 DB의 기존 승인 결과를 재응답한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1001인 기존 PaymentAttempt가 존재한다.
     * - 기존 attemptSeq=1, finalStatus=APPROVED, amount=20000이다.
     * - 기존 cardBin=42424242, cardLast4=4242, approvalNo=A151472400이다.
     *
     * When:
     * - 같은 posTrx로 amount=20000, pan=4242424242424242 승인 요청이 들어온다.
     *
     * Then:
     * - 기존 DB 승인 결과를 재응답한다.
     * - finalStatus=APPROVED, approvalNo=A151472400, cardSummary=42424242/4242를 검증한다.
     * - VAN 호출과 PAYMENT_ATTEMPT insert/update는 발생하지 않는다.
     */
    @Test
    void approve_existingApproved_samePayload_shouldReturnExistingApproved_withoutVanAndInsert() {
        String posTrx = "2376-20260521-9991-1001";
        ApproveRequest request = approveRequest(posTrx, 20000, "4242424242424242");
        PaymentAttempt existing = attempt(
                PaymentFinalStatus.APPROVED.name(),
                "A151472400",
                null,
                "42424242",
                "4242",
                1,
                20000
        );

        when(repository.findLatestByPosTrx(posTrx)).thenReturn(Optional.of(existing));

        ApproveResponse response = service.approve(request);

        assertEquals(PaymentFinalStatus.APPROVED, response.finalStatus());
        assertEquals("A151472400", response.approvalNo());
        assertEquals("42424242", response.cardSummary().cardBin());
        assertEquals("4242", response.cardSummary().cardLast4());

        verifyNoNewApprovalAttempt(posTrx);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-002
     * 기존 APPROVED attempt와 cardLast4가 다른 payload로 승인 재요청하면 CONFLICT를 반환한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1001인 기존 APPROVED attempt가 존재한다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242이다.
     *
     * When:
     * - 같은 posTrx로 amount=20000, pan=4242424211111111 승인 요청이 들어온다.
     * - amount/cardBin은 같지만 cardLast4가 다르다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - VAN 호출과 PAYMENT_ATTEMPT insert/update는 발생하지 않는다.
     */
    @Test
    void approve_existingApproved_differentCardLast4_shouldThrowConflict_withoutVanAndInsert() {
        String posTrx = "2376-20260521-9991-1001";
        ApproveRequest request = approveRequest(posTrx, 20000, "4242424211111111");

        when(repository.findLatestByPosTrx(posTrx)).thenReturn(Optional.of(approvedAttempt(1, 20000)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.approve(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifyNoNewApprovalAttempt(posTrx);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-003
     * 기존 APPROVED attempt와 amount가 다른 payload로 승인 재요청하면 CONFLICT를 반환한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1002인 기존 APPROVED attempt가 존재한다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242이다.
     *
     * When:
     * - 같은 posTrx로 amount=50000, pan=4242424242424242 승인 요청이 들어온다.
     * - 카드는 같지만 amount가 다르다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - VAN 호출과 PAYMENT_ATTEMPT insert/update는 발생하지 않는다.
     */
    @Test
    void approve_existingApproved_differentAmount_shouldThrowConflict_withoutVanAndInsert() {
        String posTrx = "2376-20260521-9991-1002";
        ApproveRequest request = approveRequest(posTrx, 50000, "4242424242424242");

        when(repository.findLatestByPosTrx(posTrx)).thenReturn(Optional.of(approvedAttempt(1, 20000)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.approve(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifyNoNewApprovalAttempt(posTrx);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-004
     * 기존 PROCESSING attempt와 다른 payload로 승인 재요청하면 CONFLICT를 반환한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1003인 기존 PaymentAttempt가 존재한다.
     * - 기존 finalStatus는 null이고, 서비스에서는 PROCESSING으로 해석된다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242이다.
     *
     * When:
     * - 같은 posTrx로 amount=20000, pan=4242424211111111 승인 요청이 들어온다.
     * - amount/cardBin은 같지만 cardLast4가 다르다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - VAN 호출과 PAYMENT_ATTEMPT insert/update는 발생하지 않는다.
     */
    @Test
    void approve_existingProcessing_differentPayload_shouldThrowConflict_withoutVanAndInsert() {
        String posTrx = "2376-20260521-9991-1003";
        ApproveRequest request = approveRequest(posTrx, 20000, "4242424211111111");
        PaymentAttempt existing = attempt(null, null, null, "42424242", "4242", 1, 20000);

        when(repository.findLatestByPosTrx(posTrx)).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.approve(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifyNoNewApprovalAttempt(posTrx);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-005
     * 기존 UNKNOWN_TIMEOUT attempt와 다른 payload로 승인 재요청하면 CONFLICT를 반환한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1004인 기존 UNKNOWN_TIMEOUT attempt가 존재한다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242이다.
     *
     * When:
     * - 같은 posTrx로 amount=20000, pan=4242424211111111 승인 요청이 들어온다.
     * - amount/cardBin은 같지만 cardLast4가 다르다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - VAN 호출과 PAYMENT_ATTEMPT insert/update는 발생하지 않는다.
     */
    @Test
    void approve_existingUnknownTimeout_differentPayload_shouldThrowConflict_withoutVanAndInsert() {
        String posTrx = "2376-20260521-9991-1004";
        ApproveRequest request = approveRequest(posTrx, 20000, "4242424211111111");
        PaymentAttempt existing = attempt(
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                null,
                "TIMEOUT",
                "42424242",
                "4242",
                1,
                20000
        );

        when(repository.findLatestByPosTrx(posTrx)).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.approve(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifyNoNewApprovalAttempt(posTrx);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-006
     * 기존 DECLINED attempt와 같은 payload로 승인 재요청하면 새 attempt를 허용한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1006인 기존 DECLINED attempt가 존재한다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242, declineCode=INSUFFICIENT_FUNDS이다.
     * - 신규 attemptSeq=2가 발급되고, VAN/DB update 결과는 APPROVED/A222222222로 mock 처리한다.
     *
     * When:
     * - 같은 posTrx로 amount=20000, pan=4242424242424242 승인 요청이 들어온다.
     *
     * Then:
     * - CONFLICT가 발생하지 않는다.
     * - 신규 attempt row insert, VAN approve, updateAttemptResult가 각각 호출된다.
     * - 응답은 DB update 결과 기준으로 APPROVED/A222222222와 cardSummary=42424242/4242를 반환한다.
     */
    @Test
    void approve_existingDeclined_samePayload_shouldAllowNewAttempt() {
        String posTrx = "2376-20260521-9991-1006";
        ApproveRequest request = approveRequest(posTrx, 20000, "4242424242424242");
        int newAttemptSeq = 2;
        VanApproveRequest vanRequest = vanApproveRequest(posTrx, newAttemptSeq, 20000, "4242424242424242");

        when(repository.findLatestByPosTrx(posTrx))
                .thenReturn(Optional.of(declinedAttempt(1, 20000)));
        when(repository.insertAttemptSeq(posTrx)).thenReturn(newAttemptSeq);
        when(vanApproveAssembler.getVanApproveRequest(posTrx, newAttemptSeq, request))
                .thenReturn(vanRequest);
        when(vanGateway.approve(vanRequest))
                .thenReturn(vanApprovedResponse(posTrx, newAttemptSeq, "A222222222", "42424242", "4242"));
        when(repository.updateAttemptResult(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedApprovedRow(posTrx, newAttemptSeq, "A222222222", "42424242", "4242")));

        ApproveResponse response = service.approve(request);

        assertEquals(PaymentFinalStatus.APPROVED, response.finalStatus());
        assertEquals("A222222222", response.approvalNo());
        assertEquals("42424242", response.cardSummary().cardBin());
        assertEquals("4242", response.cardSummary().cardLast4());

        verifyNewApprovalAttempt(posTrx, newAttemptSeq, request, vanRequest);
    }

    /**
     * [시나리오] UT-2-APPROVE-IDEMP-007
     * 기존 DECLINED attempt와 다른 payload로 승인 재요청해도 새 attempt를 허용한다.
     *
     * Given:
     * - posTrx=2376-20260521-9991-1007인 기존 DECLINED attempt가 존재한다.
     * - 기존 amount=20000, cardBin=42424242, cardLast4=4242, declineCode=INSUFFICIENT_FUNDS이다.
     * - 신규 attemptSeq=2가 발급되고, VAN/DB update 결과는 APPROVED/A333333333로 mock 처리한다.
     *
     * When:
     * - 같은 posTrx로 amount=30000, pan=4242424211111111 승인 요청이 들어온다.
     * - 기존 attempt와 amount/cardLast4가 다르다.
     *
     * Then:
     * - CONFLICT가 발생하지 않는다.
     * - 신규 attempt row insert, VAN approve, updateAttemptResult가 각각 호출된다.
     * - 응답은 DB update 결과 기준으로 APPROVED/A333333333와 cardSummary=42424242/1111을 반환한다.
     */
    @Test
    void approve_existingDeclined_differentPayload_shouldAllowNewAttempt() {
        String posTrx = "2376-20260521-9991-1007";
        ApproveRequest request = approveRequest(posTrx, 30000, "4242424211111111");
        int newAttemptSeq = 2;
        VanApproveRequest vanRequest = vanApproveRequest(posTrx, newAttemptSeq, 30000, "4242424211111111");

        when(repository.findLatestByPosTrx(posTrx))
                .thenReturn(Optional.of(declinedAttempt(1, 20000)));
        when(repository.insertAttemptSeq(posTrx)).thenReturn(newAttemptSeq);
        when(vanApproveAssembler.getVanApproveRequest(posTrx, newAttemptSeq, request))
                .thenReturn(vanRequest);
        when(vanGateway.approve(vanRequest))
                .thenReturn(vanApprovedResponse(posTrx, newAttemptSeq, "A333333333", "42424242", "1111"));
        when(repository.updateAttemptResult(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedApprovedRow(posTrx, newAttemptSeq, "A333333333", "42424242", "1111")));

        ApproveResponse response = service.approve(request);

        assertEquals(PaymentFinalStatus.APPROVED, response.finalStatus());
        assertEquals("A333333333", response.approvalNo());
        assertEquals("42424242", response.cardSummary().cardBin());
        assertEquals("1111", response.cardSummary().cardLast4());

        verifyNewApprovalAttempt(posTrx, newAttemptSeq, request, vanRequest);
    }

    /**
     * 기존 attempt 재응답 또는 충돌 차단 경로에서 신규 승인 흐름이 시작되지 않았는지 검증한다.
     */
    private void verifyNoNewApprovalAttempt(String posTrx) {
        verify(repository).findLatestByPosTrx(posTrx);
        verify(repository, never()).insertAttemptSeq(posTrx);
        verify(repository, never()).insertAttempt(any(AttemptInsertParam.class));
        verify(vanGateway, never()).approve(any(VanApproveRequest.class));
        verify(vanApproveAssembler, never()).getVanApproveRequest(any(), anyInt(), any());
        verify(repository, never()).updateAttemptResult(any(AttemptResultUpdateParam.class));
    }

    /**
     * DECLINED 이후 재시도 허용 경로에서 새 attempt 생성, VAN 호출, DB update가 수행됐는지 검증한다.
     */
    private void verifyNewApprovalAttempt(
            String posTrx,
            int attemptSeq,
            ApproveRequest request,
            VanApproveRequest vanRequest
    ) {
        verify(repository).insertAttemptSeq(posTrx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(vanApproveAssembler).getVanApproveRequest(posTrx, attemptSeq, request);
        verify(vanGateway).approve(eq(vanRequest));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));
    }

    /**
     * 테스트 시나리오별 posTrx, 금액, PAN을 받아 승인 요청 DTO를 만든다.
     */
    private ApproveRequest approveRequest(String posTrx, int amount, String pan) {
        return new ApproveRequest(posTrx, amount, new CardInput(pan, "2812"));
    }

    /**
     * 기존 DB에 APPROVED 상태로 저장된 승인 attempt fixture를 만든다.
     */
    private PaymentAttempt approvedAttempt(int attemptSeq, int amount) {
        return attempt(
                PaymentFinalStatus.APPROVED.name(),
                "A151472400",
                null,
                "42424242",
                "4242",
                attemptSeq,
                amount
        );
    }

    /**
     * 기존 DB에 DECLINED 상태로 저장된 승인 attempt fixture를 만든다.
     */
    private PaymentAttempt declinedAttempt(int attemptSeq, int amount) {
        return attempt(
                PaymentFinalStatus.DECLINED.name(),
                null,
                "INSUFFICIENT_FUNDS",
                "42424242",
                "4242",
                attemptSeq,
                amount
        );
    }

    /**
     * 상태, 카드 요약, 금액을 원하는 값으로 조합할 수 있는 PaymentAttempt 공통 fixture 생성 함수다.
     */
    private PaymentAttempt attempt(
            String finalStatus,
            String approvalNo,
            String declineCode,
            String cardBin,
            String cardLast4,
            int attemptSeq,
            int amount
    ) {
        return new PaymentAttempt(
                finalStatus,
                approvalNo,
                declineCode,
                cardBin,
                cardLast4,
                attemptSeq,
                amount,
                "VAN-TRX-0001"
        );
    }

    /**
     * 승인 재시도 허용 케이스에서 assembler가 반환할 VAN 승인 요청 fixture를 만든다.
     */
    private VanApproveRequest vanApproveRequest(String posTrx, int attemptSeq, int amount, String pan) {
        return VanApproveRequest.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .amount(amount)
                .pan(pan)
                .expiryYyMm("2812")
                .cardBin(pan.substring(0, 8))
                .cardLast4(pan.substring(pan.length() - 4))
                .build();
    }

    /**
     * VAN이 승인 성공을 반환한 상황을 표현하는 응답 fixture를 만든다.
     */
    private VanApproveResponse vanApprovedResponse(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String cardBin,
            String cardLast4
    ) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin(cardBin)
                .cardLast4(cardLast4)
                .vanResult(VanResult.APPROVED)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo(approvalNo)
                .declineCode(null)
                .vanTrxId("VAN-TRX-APPROVED")
                .message("OK")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    /**
     * updateAttemptResult가 성공했을 때 DB가 반환하는 승인 확정 row fixture를 만든다.
     */
    private PaymentAttemptUpdatedRow updatedApprovedRow(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String cardBin,
            String cardLast4
    ) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.APPROVED,
                approvalNo,
                null,
                cardBin,
                cardLast4,
                "VAN-TRX-APPROVED"
        );
    }

}
