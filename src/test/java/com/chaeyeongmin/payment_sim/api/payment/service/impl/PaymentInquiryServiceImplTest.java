package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.api.payment.validate.InquiryRequestValidator;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.ApproveValidationError;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.CancelValidationError;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.InquiryValidationError;
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

    /*
     * 이 테스트 파일에서 자주 나오는 이름의 의미:
     *
     * latest       : repository.findByPosTrxAndAttemptSeq(...)가 DB에서 읽어온 것처럼 돌려주는 기존 attempt row
     * vanInquiryReq: 서비스가 VAN 조회를 호출하기 위해 assembler에게 만들어 달라고 하는 요청 DTO
     * vanInquiryRes: gateway.inquiry(...)가 VAN에서 받은 것처럼 돌려주는 응답 DTO
     * finalizedRow : repository.updateUnknownToFinal(...) 이후 DB에 실제 저장된 최종 row
     * res          : service.inquiry(...)의 최종 API 응답
     *
     * 비교 기준:
     * - DB가 이미 APPROVED/DECLINED/PROCESSING이면 res는 latest 기준이어야 한다.
     * - DB가 UNKNOWN_TIMEOUT이고 VAN 조회 후 update가 성공하면 res는 finalizedRow 기준이어야 한다.
     * - DB가 UNKNOWN_TIMEOUT이고 VAN도 계속 UNKNOWN_TIMEOUT이면 update 없이 latest의 카드정보와 VAN의 상태/코드 기준이다.
     */

    // ===== UT IDs (최소 핵심 8개) =====
    private static final String UT_Q2_001 = "UT-PAYMENT-INQUIRY-001"; // Q2 invalid
    private static final String UT_Q3_001 = "UT-PAYMENT-INQUIRY-002"; // Q3 NOT_FOUND
    private static final String UT_Q9_001 = "UT-PAYMENT-INQUIRY-003"; // Q9 APPROVED DB 재응답
    private static final String UT_Q9_002 = "UT-PAYMENT-INQUIRY-004"; // Q9 DECLINED DB 재응답
    private static final String UT_Q10_001 = "UT-PAYMENT-INQUIRY-005"; // Q10 PROCESSING retryLater
    private static final String UT_Q7_001 = "UT-PAYMENT-INQUIRY-006"; // UNKNOWN -> VAN APPROVED -> Q6/Q7
    private static final String UT_Q7_002 = "UT-PAYMENT-INQUIRY-007"; // UNKNOWN -> VAN DECLINED -> Q6/Q7
    private static final String UT_Q8_001 = "UT-PAYMENT-INQUIRY-008"; // UNKNOWN -> VAN UNKNOWN -> Q8
    private static final String UT_Q5_005 = "UT-2-INQ-UNKNOWN-005"; // UNKNOWN -> VAN FINAL -> update miss -> reread

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
        doThrow(new BusinessException(
                ResultCode.INVALID,
                InquiryValidationError.INVALID_REQUEST.code()
        ))
                .when(validator)
                .validate(baseReq);

        // when + then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.inquiry(baseReq)
        );

        assertEquals(ResultCode.INVALID, exception.getResultCode());
        assertEquals(InquiryValidationError.INVALID_REQUEST.code(), exception.getMessage());

        verify(validator).validate(baseReq);
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
        // trx/attemptSeq는 조회 대상 row를 찾기 위한 key다.
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        // latest는 "DB에서 이미 APPROVED로 확정된 row가 조회됐다"는 가짜 DB 조회 결과다.
        // 이 시나리오에서는 VAN을 다시 보지 않고 latest 값 그대로 응답해야 한다.
        PaymentAttempt latest = latestAttempt(
                "APPROVED",
                "A123456789",
                null,
                attemptSeq
        );

        // repository mock에게 "이 key로 조회하면 latest row가 나온다"고 알려준다.
        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        // 이미 확정된 DB row 재응답이므로 응답 승인번호/카드정보는 latest와 비교한다.
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

        // latest는 "DB에서 이미 DECLINED로 확정된 row가 조회됐다"는 가짜 DB 조회 결과다.
        // 승인번호는 없고, 거절 사유 코드("05")가 응답에 재사용되어야 한다.
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
        // DECLINED 재응답이므로 응답 거절코드는 DB 조회 row인 latest와 비교한다.
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

        // latest가 PROCESSING이면 아직 확정 전이라는 뜻이다.
        // 조회 서비스는 VAN 재조회 없이 "잠시 후 다시 조회" 성격의 PROCESSING 응답을 내려야 한다.
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

        // 처리중 상태에서는 VAN inquiry도, DB 확정 update도 호출하면 안 된다.
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

        // latest는 DB에 UNKNOWN_TIMEOUT으로 남아 있던 기존 attempt row다.
        // 이 상태만 VAN 조회 대상이며, cardLast4/vanTrxId가 VAN inquiry 요청 구성에 쓰인다.
        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "42424242",
                "4242",
                "VAN-TRX-0001"
        );

        // assembler가 만들어 줄 VAN 조회 요청 DTO다.
        // 테스트에서는 assembler도 mock이라, 아래 when(...)에서 이 객체를 반환하도록 지정한다.
        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("4242")
                .build();

        // VAN이 조회 결과를 APPROVED로 돌려준 상황을 만든다.
        VanInquiryResponse vanInquiryRes = vanInquiryResApproved(trx, attemptSeq);

        // finalizedRow는 VAN 응답을 DB에 반영한 뒤 updateUnknownToFinal이 반환한 최종 DB row다.
        // Q7에서는 VAN 응답값이 아니라 이 DB RETURNING row 기준으로 API 응답을 만든다.
        PaymentAttemptUpdatedRow finalizedRow = updatedRowApproved(
                trx,
                attemptSeq,
                "DB-APPROVAL-0001",
                "99999999",
                "9999"
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        // 서비스는 latest.cardLast4/latest.vanTrxId를 assembler에 넘겨 VAN 조회 요청을 만든다.
        when(assembler.getVanInquiryRequest(trx, attemptSeq, "4242", "VAN-TRX-0001"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(finalizedRow));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        // update 성공 후 응답은 finalizedRow(DB 저장 결과)와 비교한다.
        // vanInquiryRes.approvalNo()와 비교하지 않는 점이 이 테스트의 핵심이다.
        assertEquals(finalizedRow.approvalNo(), res.approvalNo());
        assertEquals(finalizedRow.cardBin(), res.cardSummary().cardBin());
        assertEquals(finalizedRow.cardLast4(), res.cardSummary().cardLast4());

        // VAN 응답 승인번호가 아니라 DB RETURNING row 승인번호 기준인지 검증
        assertEquals("DB-APPROVAL-0001", res.approvalNo());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "4242", "VAN-TRX-0001");
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

        // UNKNOWN_TIMEOUT row라서 VAN 조회가 필요한 기존 DB row다.
        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "41111111",
                "1111",
                "VAN-TRX-0001"
        );

        // latest.cardLast4("1111")를 사용해 만들어질 VAN 조회 요청이다.
        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("1111")
                .build();

        // VAN이 조회 결과를 DECLINED로 확정해 준 상황이다.
        VanInquiryResponse vanInquiryRes = vanInquiryResDeclined(trx, attemptSeq);

        // DB update가 성공해서 DECLINED 상태로 저장된 최종 row다.
        // 응답 declineCode/card 정보는 이 row 기준으로 비교한다.
        PaymentAttemptUpdatedRow finalizedRow = updatedRowDeclined(
                trx,
                attemptSeq,
                "05",
                "88888888",
                "8881"
        );

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "1111", "VAN-TRX-0001"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(finalizedRow));

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.DECLINED, res.finalStatus());
        // VAN 응답 enum이 아니라 DB에 저장된 문자열 declineCode("05")가 응답 기준이다.
        assertEquals(finalizedRow.declineCode(), res.declineCode());
        assertEquals(finalizedRow.cardBin(), res.cardSummary().cardBin());
        assertEquals(finalizedRow.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "1111", "VAN-TRX-0001");
        verify(gateway, times(1))
                .inquiry(vanInquiryReq);
        verify(repository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-2-INQ-UNKNOWN-005
     * <p>
     * [시나리오] UT-2-INQ-UNKNOWN-005
     * - Given: DB attempt finalStatus=UNKNOWN_TIMEOUT
     * - And  : VAN inquiry 결과 finalStatus=APPROVED
     * - And  : repository.updateUnknownToFinal(...) 결과가 Optional.empty()
     * - And  : 같은 posTrx + attemptSeq 재조회 결과가 APPROVED
     * - When : service.inquiry() 호출
     * - Then : 재조회된 DB row 기준 APPROVED 응답을 반환한다
     * - And  : VAN inquiry는 최초 1회만 호출되어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(UNKNOWN_TIMEOUT) -> Q5(VAN APPROVED)
     * -> Q6(update miss) -> Q6-0rows 재조회 -> Q9(DB 현재 상태 재응답)
     */
    @Test
    void inquiry_unknownTimeout_vanApproved_updateMiss_rereadApproved_shouldReturnDbApproved() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "42424242",
                "4242",
                "VAN-TRX-UNKNOWN-0001"
        );

        PaymentAttempt rereadApproved = latestAttempt(
                "APPROVED",
                "DB-REREAD-APPROVAL-0001",
                null,
                attemptSeq,
                "99999999",
                "9999",
                "DB-REREAD-VAN-TRX-0001"
        );

        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("4242")
                .build();

        VanInquiryResponse vanInquiryRes = vanInquiryResApproved(trx, attemptSeq);

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest), Optional.of(rereadApproved));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "4242", "VAN-TRX-UNKNOWN-0001"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.empty());

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        assertEquals(rereadApproved.approvalNo(), res.approvalNo());
        assertEquals(rereadApproved.cardBin(), res.cardSummary().cardBin());
        assertEquals(rereadApproved.cardLast4(), res.cardSummary().cardLast4());

        // VAN 응답 승인번호가 아니라 update miss 이후 재조회된 DB row 기준인지 검증한다.
        assertEquals("DB-REREAD-APPROVAL-0001", res.approvalNo());

        verify(repository, times(2))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "4242", "VAN-TRX-UNKNOWN-0001");
        verify(gateway, times(1))
                .inquiry(vanInquiryReq);
        verify(repository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
    }

    /**
     * [UT_ID] UT-2-INQ-UNKNOWN-005
     * <p>
     * [시나리오] UT-2-INQ-UNKNOWN-005
     * - Given: DB attempt finalStatus=UNKNOWN_TIMEOUT
     * - And  : VAN inquiry 결과 finalStatus=DECLINED
     * - And  : repository.updateUnknownToFinal(...) 결과가 Optional.empty()
     * - And  : 같은 posTrx + attemptSeq 재조회 결과가 DECLINED
     * - When : service.inquiry() 호출
     * - Then : 재조회된 DB row 기준 DECLINED 응답을 반환한다
     * - And  : VAN inquiry는 최초 1회만 호출되어야 한다
     * <p>
     * [흐름도]
     * Q1 -> Q2 -> Q3(UNKNOWN_TIMEOUT) -> Q5(VAN DECLINED)
     * -> Q6(update miss) -> Q6-0rows 재조회 -> Q9(DB 현재 상태 재응답)
     */
    @Test
    void inquiry_unknownTimeout_vanDeclined_updateMiss_rereadDeclined_shouldReturnDbDeclined() {
        // given
        String trx = baseReq.posTrx();
        int attemptSeq = baseReq.attemptSeq();

        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "41111111",
                "1111",
                "VAN-TRX-UNKNOWN-0002"
        );

        PaymentAttempt rereadDeclined = latestAttempt(
                "DECLINED",
                null,
                "DB_REREAD_DECLINED",
                attemptSeq,
                "88888888",
                "8881",
                "DB-REREAD-VAN-TRX-0002"
        );

        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("1111")
                .build();

        VanInquiryResponse vanInquiryRes = vanInquiryResDeclined(trx, attemptSeq);

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest), Optional.of(rereadDeclined));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "1111", "VAN-TRX-UNKNOWN-0002"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        when(repository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.empty());

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.DECLINED, res.finalStatus());
        assertEquals(rereadDeclined.declineCode(), res.declineCode());
        assertEquals(rereadDeclined.cardBin(), res.cardSummary().cardBin());
        assertEquals(rereadDeclined.cardLast4(), res.cardSummary().cardLast4());

        // VAN 응답의 DO_NOT_HONOR("05")가 아니라 재조회된 DB row declineCode 기준인지 검증한다.
        assertEquals("DB_REREAD_DECLINED", res.declineCode());

        verify(repository, times(2))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "1111", "VAN-TRX-UNKNOWN-0002");
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

        // 기존 DB row도 UNKNOWN_TIMEOUT이고, 아래 VAN 응답도 UNKNOWN_TIMEOUT인 케이스다.
        // 확정 결과가 없으므로 updateUnknownToFinal은 호출되면 안 된다.
        PaymentAttempt latest = latestAttempt(
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                attemptSeq,
                "40000000",
                "0000",
                "VAN-TRX-0001"
        );

        // VAN 조회 요청은 만들지만, VAN 결과가 미확정이면 DB update는 하지 않는다.
        VanInquiryRequest vanInquiryReq = VanInquiryRequest.builder()
                .posTrx(trx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null)
                .cardLast4("0000")
                .build();

        // VAN 조회 후에도 여전히 UNKNOWN_TIMEOUT인 응답이다.
        VanInquiryResponse vanInquiryRes = vanInquiryResUnknownTimeout(trx, attemptSeq);

        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(latest));

        when(assembler.getVanInquiryRequest(trx, attemptSeq, "0000", "VAN-TRX-0001"))
                .thenReturn(vanInquiryReq);

        when(gateway.inquiry(vanInquiryReq))
                .thenReturn(vanInquiryRes);

        // when
        InquiryResponse res = service.inquiry(baseReq);

        // then
        assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, res.finalStatus());
        // Q8은 DB update가 없으므로 declineCode는 VAN 응답의 TIMEOUT 코드 기준이다.
        assertEquals("TIMEOUT", res.declineCode());
        // 카드정보는 처음 DB에서 읽은 latest row 기준으로 응답한다.
        assertEquals(latest.cardBin(), res.cardSummary().cardBin());
        assertEquals(latest.cardLast4(), res.cardSummary().cardLast4());

        verify(repository, times(1))
                .findByPosTrxAndAttemptSeq(trx, attemptSeq);
        verify(assembler, times(1))
                .getVanInquiryRequest(trx, attemptSeq, "0000", "VAN-TRX-0001");
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
        // 카드정보/vanTrxId가 중요하지 않은 재응답 테스트용 기본값이다.
        return latestAttempt(
                finalStatus,
                approveNo,
                declineCode,
                attemptSeq,
                "41111111",
                "1111",
                "VAN-TRX-0001"
        );
    }

    private PaymentAttempt latestAttempt(
            String finalStatus,
            String approveNo,
            String declineCode,
            int attemptSeq,
            String cardBin,
            String cardLast4,
            String vanTrxId
    ) {
        // PaymentAttempt는 repository 조회 결과를 흉내내는 DB row 모델이다.
        // finalStatus에 따라 서비스 분기가 달라지고, UNKNOWN_TIMEOUT에서는 cardLast4/vanTrxId가 VAN inquiry 요청에 쓰인다.
        return new PaymentAttempt(
                finalStatus,
                approveNo,
                declineCode,
                cardBin,
                cardLast4,
                "test-card-fingerprint",
                attemptSeq,
                10000,
                vanTrxId
        );
    }

    private PaymentAttemptUpdatedRow updatedRowApproved(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String cardBin,
            String cardLast4
    ) {
        // updateUnknownToFinal(...)이 APPROVED로 저장한 뒤 DB RETURNING으로 돌려준 row를 흉내낸다.
        // 서비스의 Q7 응답은 VAN 응답이 아니라 이 row의 approvalNo/card 정보 기준이어야 한다.
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
        // updateUnknownToFinal(...)이 DECLINED로 저장한 뒤 DB RETURNING으로 돌려준 row를 흉내낸다.
        // 서비스의 Q7 응답은 이 row의 declineCode/card 정보 기준이어야 한다.
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
