package com.chaeyeongmin.payment_sim.api.payment.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PaymentInquiryServiceImplTest {

    // ===== UT IDs (최소 핵심 8개) =====
    private static final String UT_Q2_001 = "UT-PAYMENT-INQUIRY-001"; // Q2 invalid
    private static final String UT_Q3_001 = "UT-PAYMENT-INQUIRY-002"; // Q3 NOT_FOUND
    private static final String UT_Q9_001 = "UT-PAYMENT-INQUIRY-003"; // Q9 APPROVED DB 재응답
    private static final String UT_Q9_002 = "UT-PAYMENT-INQUIRY-004"; // Q9 DECLINED DB 재응답
    private static final String UT_Q10_001 = "UT-PAYMENT-INQUIRY-005"; // Q10 PROCESSING retryLater
    private static final String UT_Q7_001 = "UT-PAYMENT-INQUIRY-006"; // UNKNOWN -> VAN APPROVED -> Q6/Q7
    private static final String UT_Q7_002 = "UT-PAYMENT-INQUIRY-007"; // UNKNOWN -> VAN DECLINED -> Q6/Q7
    private static final String UT_Q8_001 = "UT-PAYMENT-INQUIRY-008"; // UNKNOWN -> VAN UNKNOWN -> Q8

    private PaymentInquiryService service;
    private PaymentInquiryRepository repository;
    private VanGateway gateway;
    private InquiryRequestValidator validator;
    private VanInquiryAssembler assembler;

    private InquiryRequest baseReq;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentInquiryRepository.class);
        gateway = mock(VanGateway.class);
        validator = mock(InquiryRequestValidator.class);
        assembler = mock(VanInquiryAssembler.class);

        service = new PaymentInquiryServiceImpl(
                repository,
                gateway,
                validator,
                assembler
        );

        baseReq = new InquiryRequest("2376-20260215-9991-0201", 1);
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-001
     * <p>
     * [시나리오]
     * - Given: validator.validate()가 INVALID 계열 예외를 던진다
     * - When : service.inquiry() 호출
     * - Then : 예외가 그대로 전파된다
     * - And  : Q2에서 종료되므로 repository / vanGateway / vanInquiryAssembler 호출이 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2(FAIL) 종료
     */
    @Test
    void inquiry_Q2_invalid_shouldThrow_andNoCalls() {
        // given
        doThrow(new IllegalArgumentException("INVALID"))
                .when(validator)
                .validate(baseReq);

        // when + then
        assertThrows(
                IllegalArgumentException.class,
                () -> service.inquiry(baseReq)
        );

        verifyNoInteractions(repository, gateway, assembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-002
     * <p>
     * [시나리오]
     * - Given: repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq)가 Optional.empty()를 반환한다
     * - When : service.inquiry() 호출
     * - Then : BusinessException(ResultCode.NOT_FOUND)이 발생한다
     * - And  : 조회 대상이 없으므로 VAN inquiry 호출이 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(NOT_FOUND) 종료
     */
    @Test
    void inquiry_Q3_attemptNotFound_shouldThrowNotFound_andNoVanCall() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.empty());

        // when + then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.inquiry(baseReq)
        );

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verifyNoInteractions(gateway);
        verifyNoInteractions(assembler);
        verify(repository, never())
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-003
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=APPROVED
     * - When : service.inquiry() 호출
     * - Then : DB 값 기준으로 APPROVED 응답을 반환한다(Q9)
     * - And  : 이미 확정된 건이므로 VAN inquiry 호출이 없어야 한다
     * - And  : updateUnknownToFinal 호출도 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(APPROVED) -> Q9(DB 재응답)
     */
    @Test
    void inquiry_Q9_approvedAttempt_shouldReturnDbApproved_withoutVanCall() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "APPROVED",
                "A123456789",
                null,
                attemptSeq
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        assertEquals(latest.approvalNo(), res.approvalNo());
        assertEquals(latest.cardBin(), res.cardSummary().cardBin());
        assertEquals(latest.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verifyNoInteractions(gateway);
        verifyNoInteractions(assembler);
        verify(repository, never())
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-004
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=DECLINED
     * - When : service.inquiry() 호출
     * - Then : DB 값 기준으로 DECLINED 응답을 반환한다(Q9)
     * - And  : 이미 확정된 건이므로 VAN inquiry 호출이 없어야 한다
     * - And  : updateUnknownToFinal 호출도 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(DECLINED) -> Q9(DB 재응답)
     */
    @Test
    void inquiry_Q9_declinedAttempt_shouldReturnDbDeclined_withoutVanCall() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "DECLINED",
                null,
                "05",
                attemptSeq
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.DECLINED, res.finalStatus());
        assertEquals(latest.declineCode(), res.declineCode());
        assertEquals(latest.cardBin(), res.cardSummary().cardBin());
        assertEquals(latest.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verifyNoInteractions(gateway);
        verifyNoInteractions(assembler);
        verify(repository, never())
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-005
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=PROCESSING
     * - When : service.inquiry() 호출
     * - Then : retryLater 성격의 PROCESSING 응답을 반환한다(Q10)
     * - And  : 아직 처리중이므로 VAN inquiry 호출이 없어야 한다
     * - And  : updateUnknownToFinal 호출도 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(PROCESSING) -> Q10(retryLater)
     */
    @Test
    void inquiry_Q10_processingAttempt_shouldReturnRetryLater_withoutVanCall() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "PROCESSING",
                null,
                null,
                attemptSeq
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.PROCESSING, res.finalStatus());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verifyNoInteractions(gateway);
        verifyNoInteractions(assembler);
        verify(repository, never())
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-006
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=UNKNOWN_TIMEOUT
     * - And  : VAN inquiry 결과 finalStatus=APPROVED
     * - And  : repository.updateUnknownToFinal(...) returning 성공
     * - When : service.inquiry() 호출
     * - Then : DB RETURNING row 기준 APPROVED 응답을 반환한다(Q7)
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(UNKNOWN_TIMEOUT)
     * -> Q5(VAN APPROVED) -> Q6(update 성공) -> Q7(APPROVED 응답)
     */
    @Test
    void inquiry_unknownTimeout_vanApproved_updateSuccess_shouldReturnApproved_Q7() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "42424242",
                "4242"
        );

        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("4242")
                .build();

        VanInquiryResponse vanInquiryRes = vanInquiryResApproved(trx, attemptSeq);

        PaymentAttemptUpdatedRow finalizedRow = updatedRowApproved(
                trx,
                attemptSeq,
                "DB-APPROVAL-0001",
                "99999999",
                "9999"
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "4242"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(finalizedRow));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        assertEquals(finalizedRow.approvalNo(), res.approvalNo());
        assertEquals(finalizedRow.cardBin(), res.cardSummary().cardBin());
        assertEquals(finalizedRow.cardLast4(), res.cardSummary().cardLast4());

        // VAN 응답 승인번호가 아니라 DB RETURNING row 승인번호 기준인지 검증
        assertEquals("DB-APPROVAL-0001", res.approvalNo());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "4242");
        verify(gateway, times(1))
                .inquiry(vanInquiryReq);
        verify(repository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-007
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=UNKNOWN_TIMEOUT
     * - And  : VAN inquiry 결과 finalStatus=DECLINED
     * - And  : repository.updateUnknownToFinal(...) returning 성공
     * - When : service.inquiry() 호출
     * - Then : DB RETURNING row 기준 DECLINED 응답을 반환한다(Q7)
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(UNKNOWN_TIMEOUT)
     * -> Q5(VAN DECLINED) -> Q6(update 성공) -> Q7(DECLINED 응답)
     */
    @Test
    void inquiry_unknownTimeout_vanDeclined_updateSuccess_shouldReturnDeclined_Q7() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "41111111",
                "1111"
        );

        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("1111")
                .build();

        VanInquiryResponse vanInquiryRes = vanInquiryResDeclined(trx, attemptSeq);

        PaymentAttemptUpdatedRow finalizedRow = updatedRowDeclined(
                trx,
                attemptSeq,
                "05",
                "88888888",
                "8881"
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "1111"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(finalizedRow));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.DECLINED, res.finalStatus());
        assertEquals(finalizedRow.declineCode(), res.declineCode());
        assertEquals(finalizedRow.cardBin(), res.cardSummary().cardBin());
        assertEquals(finalizedRow.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "1111");
        verify(gateway, times(1))
                .inquiry(vanInquiryReq);
        verify(repository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-PAYMENT-INQUIRY-008
     * <p>
     * [시나리오]
     * - Given: DB attempt finalStatus=UNKNOWN_TIMEOUT
     * - And  : VAN inquiry 결과도 finalStatus=UNKNOWN_TIMEOUT
     * - When : service.inquiry() 호출
     * - Then : UNKNOWN_TIMEOUT 응답을 반환한다(Q8)
     * - And  : DB 상태 유지 케이스이므로 updateUnknownToFinal 호출이 없어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(대상 존재) -> Q4(UNKNOWN_TIMEOUT)
     * -> Q5(VAN UNKNOWN_TIMEOUT) -> Q8(미확정 유지)
     */
    @Test
    void inquiry_unknownTimeout_vanStillUnknown_shouldReturnUnknownTimeout_withoutUpdate_Q8() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "40000000",
                "0000"
        );

        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("0000")
                .build();

        VanInquiryResponse vanInquiryRes = vanInquiryResUnknownTimeout(trx, attemptSeq);

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "0000"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, res.finalStatus());
        assertEquals("TIMEOUT", res.declineCode());
        assertEquals(latest.cardBin(), res.cardSummary().cardBin());
        assertEquals(latest.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "0000");
        verify(gateway, times(1))
                .inquiry(vanInquiryReq);
        verify(repository, never())
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    private PaymentAttempt latestAttempt(
            String finalStatus,
            String approveNo,
            String declineCode,
            int attemptSeq
    ) {
        return latestAttempt(
                finalStatus,
                approveNo,
                declineCode,
                attemptSeq,
                "41111111",
                "1111"
        );
    }

    private PaymentAttempt latestAttempt(
            String finalStatus,
            String approveNo,
            String declineCode,
            int attemptSeq,
            String cardBin,
            String cardLast4
    ) {
        return new PaymentAttempt(
                finalStatus,
                approveNo,
                declineCode,
                cardBin,
                cardLast4,
                attemptSeq,
                10000
        );
    }

    private PaymentAttemptUpdatedRow updatedRowApproved(
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
                posTrx + "-" + String.format("%02d", attemptSeq)
        );
    }

    private PaymentAttemptUpdatedRow updatedRowDeclined(
            String posTrx,
            int attemptSeq,
            String declineCode,
            String cardBin,
            String cardLast4
    ) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.DECLINED,
                null,
                declineCode,
                cardBin,
                cardLast4,
                posTrx + "-" + String.format("%02d", attemptSeq)
        );
    }

    private VanInquiryResponse vanInquiryResApproved(String posTrx, int attemptSeq) {
        return VanInquiryResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo("VAN-APPROVAL-0001")
                .declineCode(null)
                .vanTrxId("VAN-INQ-TRX-0001")
                .message("APPROVED_BY_INQUIRY")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanInquiryResponse vanInquiryResDeclined(String posTrx, int attemptSeq) {
        return VanInquiryResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .approvalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("VAN-INQ-TRX-0002")
                .message("DECLINED_BY_INQUIRY")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanInquiryResponse vanInquiryResUnknownTimeout(String posTrx, int attemptSeq) {
        return VanInquiryResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                .approvalNo(null)
                .declineCode(VanDeclineCode.TIMEOUT)
                .vanTrxId("VAN-INQ-TRX-0003")
                .message("STILL_UNKNOWN")
                .respondedAt(LocalDateTime.now())
                .build();
    }
}