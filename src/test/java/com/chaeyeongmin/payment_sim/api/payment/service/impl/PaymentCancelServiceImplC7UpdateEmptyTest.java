package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelCardMatchPolicy;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PaymentCancelServiceImplC7UpdateEmptyTest {

    private PaymentCancelService service;
    private PaymentCancelRepository repository;
    private VanGateway vanGateway;
    private CancelRequestValidator validator;
    private VanCancelAssembler vanCancelAssembler;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    private CancelRequest baseReq;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancelRepository.class);
        vanGateway = mock(VanGateway.class);
        validator = mock(CancelRequestValidator.class);
        vanCancelAssembler = mock(VanCancelAssembler.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);

        service = new PaymentCancelServiceImpl(
                repository,
                vanGateway,
                validator,
                vanCancelAssembler,
                paymentEventLogRecorder,
                new CancelCardMatchPolicy()
        );

        baseReq = new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                "4242424242424242"
        );
    }

    /**
     * [시나리오] C7 update empty 이후 재조회 결과가 PENDING인 경우.
     *
     * - Given: 원거래는 APPROVED이고, C4 최초 기존 cancel row 조회는 empty다.
     * - And  : C5 PENDING insert는 성공한다.
     * - And  : VAN은 CANCELLED를 응답한다.
     * - And  : C7 updateCancelResult는 Optional.empty를 반환한다.
     * - When : original 기준으로 재조회해보니 아직 PENDING이다.
     * - Then : DB 최종 확정 상태가 보이지 않으므로 RETRY_LATER를 응답한다.
     * - And  : C7은 이미 VAN을 호출한 흐름이므로 VAN cancel 호출은 총 1회여야 한다.
     */
    @Test
    void cancel_C7_updateEmpty_rereadPending_shouldReturnRetryLater() {
        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 "기존 cancel row 없음"으로 응답해 C5 insert까지 내려가게 만든다.
        givenCancelMockData(Optional.of(pendingCancel()), vanCancelResCancelled());

        CancelResponse res = service.cancel(baseReq);

        verifyCancelMockData();
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        assertNull(res.cancelApprovalNo());
        assertNull(res.declineCode());

    }

    /**
     * [시나리오] C7 update empty 이후 재조회 결과가 CANCELLED인 경우.
     *
     * - Given: C5 PENDING insert 성공 후 VAN cancel 호출까지 완료됐다.
     * - And  : VAN은 CANCELLED를 응답했지만 C7 updateCancelResult는 Optional.empty를 반환한다.
     * - When : original 기준 재조회 결과가 CANCELLED다.
     * - Then : 이 요청은 VAN cancel을 호출한 요청이므로 ALREADY_CANCELLED가 아니라 CANCELLED를 응답한다.
     */
    @Test
    void cancel_C7_updateEmpty_rereadCancelled_shouldReturnCancelled() {
        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 "기존 cancel row 없음"으로 응답해 C5 insert까지 내려가게 만든다.
        PaymentCancel recoveredCancel = cancelledCancel();
        givenCancelMockData(Optional.of(recoveredCancel), vanCancelResCancelled());

        CancelResponse res = service.cancel(baseReq);

        verifyCancelMockData();
        assertEquals(CancelResultStatus.CANCELLED, res.cancelStatus());
        assertEquals(recoveredCancel.cancelApprovalNo(), res.cancelApprovalNo());

    }

    /**
     * [시나리오] C7 update empty 이후 재조회 결과가 CANCEL_DECLINED인 경우.
     *
     * - Given: C5 PENDING insert 성공 후 VAN cancel 호출까지 완료됐다.
     * - And  : VAN은 CANCEL_DECLINED를 응답했지만 C7 updateCancelResult는 Optional.empty를 반환한다.
     * - When : original 기준 재조회 결과가 CANCEL_DECLINED다.
     * - Then : DB에서 확인된 거절 확정 상태와 declineCode 기준으로 CANCEL_DECLINED를 응답한다.
     */
    @Test
    void cancel_C7_updateEmpty_rereadDeclined_shouldReturnCancelDeclined() {
        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 "기존 cancel row 없음"으로 응답해 C5 insert까지 내려가게 만든다.
        PaymentCancel recoveredCancel = cancelDeclinedCancel();
        givenCancelMockData(Optional.of(recoveredCancel), vanCancelResDeclined());

        CancelResponse res = service.cancel(baseReq);

        verifyCancelMockData();
        assertEquals(CancelResultStatus.CANCEL_DECLINED, res.cancelStatus());
        assertEquals(recoveredCancel.declineCode(), res.declineCode());

    }

    /**
     * [시나리오] C7 update empty 이후 original 기준 재조회도 empty인 경우.
     *
     * - Given: C5 PENDING insert 성공 후 VAN cancel 호출까지 완료됐다.
     * - And  : C7 updateCancelResult는 Optional.empty를 반환한다.
     * - When : original 기준으로 다시 조회해도 PAYMENT_CANCEL row가 없다.
     * - Then : 정합성 이상 가능성이 있으므로 서비스는 방어적으로 RETRY_LATER를 응답한다.
     */
    @Test
    void cancel_C7_updateEmpty_rereadEmpty_shouldReturnRetryLater() {
        givenApprovedOriginal();
        givenCancelMockData(Optional.empty(), vanCancelResCancelled());

        CancelResponse res = service.cancel(baseReq);

        verifyCancelMockData();
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        assertNull(res.cancelApprovalNo());
        assertNull(res.declineCode());

    }

    private void givenApprovedOriginal() {
        when(repository.findOriginalAttempt(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.of(originalApprovedAttempt()));
    }

    private void givenCancelMockData(
            Optional<PaymentCancel> recoveredCancel,
            VanCancelResponse vanCancelResponse
    ) {
        // 같은 original 기준 조회가 총 2번 호출되는 흐름을 만든다.
        // 1회차: C4 기존 cancel row 확인 -> empty여야 C5 신규 취소로 진행한다.
        // 2회차: C7 update empty 후 복구 재조회 -> 테스트 케이스별 recoveredCancel을 반환한다.
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq())
            )
                .thenReturn(Optional.empty())
                .thenReturn(recoveredCancel);

        // C5 insert 성공
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.of(pendingCancel()));

        // C6 VAN 요청 조립
        when(vanCancelAssembler.getVanCancelRequest(any(), any()))
                .thenReturn(vanCancelRequest());

        // C6 VAN 취소 응답
        when(vanGateway.cancel(any(VanCancelRequest.class)))
                .thenReturn(vanCancelResponse);

        // C7 update empty
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
    }

    private void verifyCancelMockData() {
        // C7 update empty는 C5 conflict와 다르다.
        // C5 conflict는 VAN 호출 전 insert 권한 획득에 실패한 흐름이고,
        // C7 update empty는 이미 VAN cancel을 1회 호출한 뒤 DB update 결과만 놓친 흐름이다.
        verify(vanCancelAssembler, times(1))
                .getVanCancelRequest(any(), any());

        verify(vanGateway, times(1))
                .cancel(any(VanCancelRequest.class));

        verify(repository, times(1))
                .updateCancelResult(any(CancelResultUpdateParam.class));

        verify(repository, times(2))
                .findByOriginalPosTrxAndOriginalAttemptSeq(
                        baseReq.originalPosTrx(),
                        baseReq.originalAttemptSeq()
                );
    }

    private PaymentAttempt originalApprovedAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                "42424242",
                "4242",
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );

    }

    private PaymentCancel paymentCancel(
            CancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                status,
                cancelApprovalNo,
                declineCode
        );
    }

    private PaymentCancel pendingCancel() {
        return paymentCancel(CancelStatus.PENDING, null, null);
    }

    private PaymentCancel cancelledCancel() {
        return paymentCancel(CancelStatus.CANCELLED, "A137515458", null);
    }

    private PaymentCancel cancelDeclinedCancel() {
        return paymentCancel(CancelStatus.CANCEL_DECLINED, null, "05");
    }

    private VanCancelResponse vanCancelResCancelled() {
        return VanCancelResponse.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCELLED)
                .cancelApprovalNo("VAN-CANCEL-APPROVAL-0001")
                .declineCode(null)
                .vanTrxId("2376-20260519-9991-1001-01")
                .message("CANCELLED_BY_VAN")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanCancelRequest vanCancelRequest() {
        return VanCancelRequest.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .amount(10000)
                .approvalNo("A207076083")
                .vanTrxId("2376-20260519-9991-1001-01")
                .cardLast4("4242")
                .build();
    }

    private VanCancelResponse vanCancelResDeclined() {
        return VanCancelResponse.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCEL_DECLINED)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("2376-20260519-9991-1001-01")
                .message("CANCEL_DECLINED_BY_VAN")
                .respondedAt(LocalDateTime.now())
                .build();
    }

}
