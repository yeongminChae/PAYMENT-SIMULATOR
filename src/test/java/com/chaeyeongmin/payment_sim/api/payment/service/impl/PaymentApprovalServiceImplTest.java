package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
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
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PaymentApprovalServiceImplTest {

    // ===== UT IDs (최소 핵심 8개) =====
    private static final String UT_A2_001 = "UT-PAYMENT-APPROVE-001"; // A2 invalid
    private static final String UT_A4_001 = "UT-PAYMENT-APPROVE-002"; // A4 APPROVED -> A9
    private static final String UT_A4_002 = "UT-PAYMENT-APPROVE-003"; // A4 PROCESSING -> A10
    private static final String UT_A4_003 = "UT-PAYMENT-APPROVE-004"; // A4 DECLINED -> 새 attempt 허용
    private static final String UT_A8_001 = "UT-PAYMENT-APPROVE-005"; // 신규 -> APPROVED -> A8
    private static final String UT_A8_002 = "UT-PAYMENT-APPROVE-006"; // 신규 -> DECLINED -> A8
    private static final String UT_A7_001 = "UT-PAYMENT-APPROVE-007"; // A7 empty + reread APPROVED -> A9
    private static final String UT_A7_002 = "UT-PAYMENT-APPROVE-008"; // A7 empty + reread processing -> A10

    // (repo, vanGateway, validator, assembler 주입)
    private PaymentApprovalService service;
    private PaymentAttemptRepository repository;
    private VanGateway gateway;
    private ApproveRequestValidator validator;
    private VanApproveAssembler assembler;

    // 기본 정상 요청 (필드 세팅은 각 테스트에서 수정해서 사용)
    private ApproveRequest baseReq;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        gateway = mock(VanGateway.class);
        validator = mock(ApproveRequestValidator.class);
        assembler = mock(VanApproveAssembler.class);

        service = new PaymentApprovalServiceImpl(repository, gateway, validator, assembler);

        baseReq = new ApproveRequest(
                "2376-20260118-9991-0023",
                10000,
                new CardInput("4111111111111111", "2812") // 정상 Luhn 예시
        );
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-001
     * <p>
     * [시나리오]
     * - Given: validator.validate()가 예외를 던짐(INVALID_*)
     * - When : service.approve() 호출
     * - Then : 예외가 그대로 전파됨
     * - And  : A2에서 끊기므로 repo/van/assembler 호출이 없어야 함
     * <p>
     * [흐름도]
     * A1 -> A2(FAIL) 종료
     */
    @Test
    void approve_A2_invalid_shouldThrow_andNoCalls() {
        // given: validator.validate(baseReq) 호출 시 무조건 INVALID_CARD 예외를 던지도록 설정
        doThrow(new IllegalArgumentException("INVALID_CARD"))
                .when(validator)
                .validate(baseReq);

        // when + then
        assertThrows(IllegalArgumentException.class, () -> service.approve(baseReq));
        verifyNoInteractions(repository, gateway, assembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-002
     * <p>
     * [시나리오]
     * - Given: repo.findLatestByPosTrx(trx) = Optional.of(latest), latest.finalStatus=APPROVED
     * - When : approve()
     * - Then : DB 값으로 즉시 응답(A9)
     * - And  : insertAttemptSeq/insertAttempt/vanGateway/updateAttemptResult 호출 0회
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(APPROVED) -> A9(재응답)
     */
    @Test
    void approve_A4_latestApproved_shouldReturn_A9() {
        // given
        // - validator.validate 통과
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        // 서비스가 DB 매핑용 DTO를 직접 new로 만들면 안됨
        // 테스트나 변환 계층(assembler/mapper)에서만 new로 객체 생성
        PaymentAttempt latest = latestAttempt("APPROVED", "A123456789", null, attemptSeq);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.of(latest));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());

        verify(repository, times(1)).findLatestByPosTrx(trx);
        verify(repository, never()).insertAttempt(any(AttemptInsertParam.class));
        verifyNoInteractions(gateway);
        verify(assembler, never()).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(repository, never()).updateAttemptResult(any(AttemptResultUpdateParam.class));

    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-003
     * <p>
     * [시나리오]
     * - Given: latest.finalStatus=PROCESSING(또는 null이 PROCESSING으로 매핑되는 케이스)
     * - When : approve()
     * - Then : retryLater(A10)
     * - And  : 신규흐름(A3~A7) 호출 0회
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(PROCESSING) -> A10(retryLater)
     */
    @Test
    void approve_A4_latestProcessing_shouldReturn_A10_retryLater() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        PaymentAttempt latest = latestAttempt("PROCESSING", null, null, attemptSeq);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.of(latest));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.PROCESSING, res.finalStatus());

        verify(repository, times(1)).findLatestByPosTrx(trx);
        verify(repository, never()).insertAttempt(any(AttemptInsertParam.class));
        verifyNoInteractions(gateway);
        verify(assembler, never()).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(repository, never()).updateAttemptResult(any(AttemptResultUpdateParam.class));

    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-004
     * <p>
     * [시나리오]
     * - Given: latest.finalStatus=DECLINED
     * - When : approve()
     * - Then : "새 attempt 허용" 정책으로 A3~ 흐름 진행
     * - And  : insertAttemptSeq/insertAttempt/assembler/vanGateway/updateAttemptResult가 호출됨
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(DECLINED) -> A3 -> A5 -> A6 -> A7 -> A8
     */
    @Test
    void approve_A4_latestDeclined_shouldAllowNewAttempt_andProceed() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;
        int newAttemptSeq = 2;

        PaymentAttempt latest = latestAttempt("DECLINED", null, "05", attemptSeq);
        VanApproveRequest vanReq = vanApproveReq(trx, newAttemptSeq, 10000);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.of(latest));
        when(repository.insertAttemptSeq(trx)).thenReturn(newAttemptSeq);
        when(assembler.getVanApproveRequest(trx, newAttemptSeq, baseReq)).thenReturn(vanReq);
        when(gateway.approve(eq(vanReq))).thenReturn(vanApproveResApproved(trx, newAttemptSeq));
        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRowApproved(trx, newAttemptSeq)));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(newAttemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());

        verify(repository).findLatestByPosTrx(trx);
        verify(repository).insertAttemptSeq(trx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(assembler).getVanApproveRequest(trx, newAttemptSeq, baseReq);
        verify(gateway).approve(eq(vanReq));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));

    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-005
     * <p>
     * [시나리오]
     * - Given: 신규(trx에 기존 attempt 없음)
     * - When : approve()
     * - Then : VAN APPROVED -> A7 returning 성공 -> approved 응답(A8)
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(신규) -> A3 -> A5 -> A6(APPROVED) -> A7(returning) -> A8
     */
    @Test
    void approve_newRequest_vanApproved_updateReturning_success_shouldReturnApproved_A8() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        VanApproveRequest vanReq = vanApproveReq(trx, attemptSeq, 10000);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.empty());
        when(repository.insertAttemptSeq(trx)).thenReturn(attemptSeq);
        when(assembler.getVanApproveRequest(trx, attemptSeq, baseReq)).thenReturn(vanReq);
        when(gateway.approve(eq(vanReq))).thenReturn(vanApproveResApproved(trx, attemptSeq));
        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRowApproved(trx, attemptSeq)));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());

        verify(repository).findLatestByPosTrx(trx);
        verify(repository).insertAttemptSeq(trx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(assembler).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(gateway).approve(eq(vanReq));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));
        verify(repository, never()).findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-006
     * <p>
     * [시나리오]
     * - Given: 신규
     * - When : VAN DECLINED -> A7 returning 성공
     * - Then : declined 응답(A8)
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(신규) -> A3 -> A5 -> A6(DECLINED) -> A7(returning) -> A8
     */
    @Test
    void approve_newRequest_vanDeclined_updateReturning_success_shouldReturnDeclined_A8() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        VanApproveRequest vanReq = vanApproveReq(trx, attemptSeq, 10000);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.empty());
        when(repository.insertAttemptSeq(trx)).thenReturn(attemptSeq);
        when(assembler.getVanApproveRequest(trx, attemptSeq, baseReq)).thenReturn(vanReq);
        when(gateway.approve(eq(vanReq))).thenReturn(vanApproveResDeclined(trx, attemptSeq));
        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRowDeclined(trx, attemptSeq)));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.DECLINED, res.finalStatus());

        verify(repository).findLatestByPosTrx(trx);
        verify(repository).insertAttemptSeq(trx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(assembler).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(gateway).approve(eq(vanReq));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));
        verify(repository, never()).findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-007
     * <p>
     * [시나리오]
     * - Given: 신규 흐름으로 VAN까지 다녀옴
     * - But  : updateAttemptResult = Optional.empty() (0 rows)
     * - And  : 재조회(findLatestByPosTrxAndAttemptSeq) 결과 finalStatus=APPROVED
     * - Then : DB 값 기준으로 재응답(A9)
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(신규) -> A3 -> A5 -> A6(APPROVED) -> A7(empty) -> A9(재응답)
     */
    @Test
    void approve_A7_empty_thenRereadApproved_shouldReturn_A9() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        PaymentAttempt latest = latestAttempt("APPROVED", "A123456789", null, attemptSeq);
        VanApproveRequest vanReq = vanApproveReq(trx, attemptSeq, 10000);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.empty());
        when(repository.insertAttemptSeq(trx)).thenReturn(attemptSeq);
        when(assembler.getVanApproveRequest(trx, attemptSeq, baseReq)).thenReturn(vanReq);
        when(gateway.approve(eq(vanReq))).thenReturn(vanApproveResApproved(trx, attemptSeq));
        when(repository.updateAttemptResult(any())).thenReturn(Optional.empty());
        when(repository.findLatestByPosTrxAndAttemptSeq(trx, attemptSeq)).thenReturn(Optional.of(latest));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        assertEquals(PaymentFinalStatus.APPROVED, res.finalStatus());
        assertEquals("A123456789", res.approvalNo());

        verify(repository).findLatestByPosTrx(trx);
        verify(repository).insertAttemptSeq(trx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(assembler).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(gateway).approve(eq(vanReq));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));
        verify(repository).findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-008
     * <p>
     * [시나리오]
     * - Given: 신규 흐름으로 VAN까지 다녀옴
     * - But  : updateAttemptResult empty(0 rows)
     * - And  : 재조회 결과 finalStatus=null(or processing)
     * - Then : retryLater(A10)
     * <p>
     * [흐름도]
     * A1 -> A2 -> A4(신규) -> A3 -> A5 -> A6 -> A7(empty) -> A10(retryLater)
     */
    @Test
    void approve_A7_empty_thenRereadProcessing_shouldReturn_A10_retryLater() {
        // given
        String trx = baseReq.getPosTrx();
        int attemptSeq = 1;

        PaymentAttempt latest = latestAttempt("PROCESSING", null, null, attemptSeq);
        VanApproveRequest vanReq = vanApproveReq(trx, attemptSeq, 10000);

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.empty());
        when(repository.insertAttemptSeq(trx)).thenReturn(attemptSeq);
        when(assembler.getVanApproveRequest(trx, attemptSeq, baseReq)).thenReturn(vanReq);
        when(gateway.approve(eq(vanReq))).thenReturn(vanApproveResApproved(trx, attemptSeq));
        when(repository.updateAttemptResult(any())).thenReturn(Optional.empty());
        when(repository.findLatestByPosTrxAndAttemptSeq(trx, attemptSeq)).thenReturn(Optional.of(latest));

        // when
        ApproveResponse res = service.approve(baseReq);

        // then
        assertEquals(trx, res.posTrx());
        assertEquals(attemptSeq, res.attemptSeq());
        // TODO : 20260215 retryLater는 PaymentFinalStatus 안에 없음, 구현 예정
        assertEquals(PaymentFinalStatus.PROCESSING, res.finalStatus());

        verify(repository).findLatestByPosTrx(trx);
        verify(repository).insertAttemptSeq(trx);
        verify(repository).insertAttempt(any(AttemptInsertParam.class));
        verify(assembler).getVanApproveRequest(trx, attemptSeq, baseReq);
        verify(gateway).approve(eq(vanReq));
        verify(repository).updateAttemptResult(any(AttemptResultUpdateParam.class));
        verify(repository).findLatestByPosTrxAndAttemptSeq(trx, attemptSeq);
    }

    // 테스트용 객체 생성 메소드

    // status만 바꿔서 새 record를 생성
    private PaymentAttempt latestAttempt(String finalStatus, String approveNo, String declineCode, int attemptSeq) {
        return new PaymentAttempt(
                finalStatus,
                approveNo, // approvalNo (필요 없으면 null로 바꿔도 됨)
                declineCode,         // declineCode
                "411111",      // cardBin
                "1111",        // cardLast4
                attemptSeq,
                10000
        );
    }

    private VanApproveRequest vanApproveReq(String posTrx, int attemptSeq, int amount) {
        // @Builder가 있으니 builder로 생성 (가독성 좋음)
        return VanApproveRequest.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .amount(amount)
                // 테스트에서는 민감정보(PAN)도 더미로만 (로그/출력 금지)
                .pan("4111111111111111")
                .expiryYyMm("2812")
                .cardBin("411111")
                .cardLast4("1111")
                .build();
    }

    private VanApproveResponse vanApproveResApproved(String posTrx, int attemptSeq) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin("411111")
                .cardLast4("1111")
                .vanResult(VanResult.APPROVED)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo("A123456789")
                .declineCode(null)              // 승인 성공이면 보통 null
                .vanTrxId("VAN-TRX-0001")
                .message("OK")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanApproveResponse vanApproveResDeclined(String posTrx, int attemptSeq) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin("411111")
                .cardLast4("1111")
                .vanResult(VanResult.DECLINED)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .approvalNo(null)
                // declineCode 타입이 VanDeclineCode enum이면 거기에 맞춰 사용
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("VAN-TRX-0002")
                .message("DECLINED")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanApproveResponse vanApproveResTimeout(String posTrx, int attemptSeq) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin("411111")
                .cardLast4("1111")
                .vanResult(VanResult.TIMEOUT)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT) // 정책 enum에 맞춰 수정
                .approvalNo(null)
                .declineCode(VanDeclineCode.TIMEOUT)             // 예시: TIMEOUT (없으면 수정)
                .vanTrxId(null)
                .message("TIMEOUT")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    /**
     * updateAttemptResult(...)가 "승자 row"로 반환할 더미.
     * - 서비스가 이 row를 기반으로 최종 ApproveResponse를 만들 가능성이 높으니
     * finalStatus/approvalNo/declineCode/bin/last4 정도는 넣어주는 게 안전.
     */
    private PaymentAttemptUpdatedRow updatedRowApproved(String posTrx, int attemptSeq) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.APPROVED,
                "A123456789",
                null,
                "411111",
                "1111",
                "VAN-TRX-0001"
        );
    }

    private PaymentAttemptUpdatedRow updatedRowDeclined(String posTrx, int attemptSeq) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.DECLINED,
                null,
                "05",           // declineCode는 여기 String이니까 "05"/"TIMEOUT" 같은 값
                "411111",
                "1111",
                "VAN-TRX-0002"
        );
    }

    private PaymentAttemptUpdatedRow updatedRowTimeout(String posTrx, int attemptSeq) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.UNKNOWN_TIMEOUT, // enum에 맞춰 수정
                null,
                "TIMEOUT",
                "411111",
                "1111",
                null
        );
    }

}