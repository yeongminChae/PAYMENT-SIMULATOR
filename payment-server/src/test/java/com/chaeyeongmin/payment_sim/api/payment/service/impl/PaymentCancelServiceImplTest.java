package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentCancelTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.CancelValidationError;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentCancelServiceImplTest {

    private PaymentCancelService service;
    private PaymentCancelTransactionService transactionService;
    private VanGateway vanGateway;
    private CancelRequestValidator validator;
    private VanCancelAssembler vanCancelAssembler;
    private CancelEventRecorder recorder;

    private CancelRequest baseReq;

    @BeforeEach
    void setUp() {
        transactionService = mock(PaymentCancelTransactionService.class);
        vanGateway = mock(VanGateway.class);
        validator = mock(CancelRequestValidator.class);
        vanCancelAssembler = mock(VanCancelAssembler.class);
        recorder = mock(CancelEventRecorder.class);

        service = new PaymentCancelServiceImpl(
                transactionService,
                vanGateway,
                validator,
                vanCancelAssembler,
                recorder
        );

        baseReq = new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                "4242424242424242"
        );
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-001
     * <p>
     * [시나리오]
     * - Given: validator.validate()가 INVALID 계열 예외를 던진다
     * - When : service.cancel() 호출
     * - Then : 예외가 그대로 전파된다
     * - And  : C2에서 종료되므로 TX1 / VAN / TX2 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2(FAIL) 종료
     */
    @Test
    @DisplayName("취소 요청 검증에 실패하면 예외를 전파하고 TX1과 VAN을 호출하지 않는다")
    void cancel_C2_invalid_shouldThrow_andNoCalls() {
        doThrow(new BusinessException(
                ResultCode.INVALID,
                CancelValidationError.INVALID_REQUEST.code()
        ))
                .when(validator)
                .validate(baseReq);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(baseReq)
        );

        assertEquals(ResultCode.INVALID, exception.getResultCode());
        assertEquals(CancelValidationError.INVALID_REQUEST.code(), exception.getMessage());

        verify(validator).validate(baseReq);
        verifyNoInteractions(transactionService, vanGateway, vanCancelAssembler, recorder);
    }

    @Test
    @DisplayName("TX1에서 완료 응답이 결정되면 VAN과 TX2를 호출하지 않고 그대로 반환한다")
    void cancel_prepareCompleted_shouldReturnResponse_withoutVanAndFinalize() {
        CancelResponse completedResponse = CancelResponse.retryLater(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq()
        );
        when(transactionService.prepare(baseReq))
                .thenReturn(PaymentCancelPrepareResult.completed(completedResponse));

        CancelResponse response = service.cancel(baseReq);

        assertSame(completedResponse, response);
        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());

        verify(validator).validate(baseReq);
        verify(transactionService).prepare(baseReq);
        verify(transactionService, never()).finalizeCancel(any(), any());
        verifyNoInteractions(vanGateway, vanCancelAssembler, recorder);
    }

    @Test
    @DisplayName("신규 취소 준비가 끝나면 VAN을 호출하고 TX2에서 최종 응답을 확정한다")
    void cancel_prepareCreated_shouldCallVanAndFinalize() {
        PaymentAttempt originalAttempt = originalApprovedAttempt();
        PaymentCancelPrepareResult prepared = PaymentCancelPrepareResult.created(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                originalAttempt
        );
        VanCancelRequest vanRequest = vanCancelRequest();
        VanCancelResponse vanResponse = vanCancelResCancelled();
        CancelResponse finalizedResponse = CancelResponse.cancelled(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                "VAN-CANCEL-APPROVAL-0001"
        );

        when(transactionService.prepare(baseReq)).thenReturn(prepared);
        when(vanCancelAssembler.assemble(
                prepared.posTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq(),
                prepared.originalAttempt()
        )).thenReturn(vanRequest);
        when(vanGateway.cancel(vanRequest)).thenReturn(vanResponse);
        when(transactionService.finalizeCancel(prepared, vanResponse)).thenReturn(finalizedResponse);

        CancelResponse response = service.cancel(baseReq);

        assertSame(finalizedResponse, response);
        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals("VAN-CANCEL-APPROVAL-0001", response.cancelApprovalNo());

        verify(validator).validate(baseReq);
        verify(transactionService).prepare(baseReq);
        verify(vanCancelAssembler).assemble(
                prepared.posTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq(),
                prepared.originalAttempt()
        );
        verify(recorder).recordCancelEvent(
                eq(PaymentEventType.CANCEL_VAN_REQUESTED),
                eq(baseReq.posTrx()),
                eq(baseReq.originalPosTrx()),
                eq(baseReq.originalAttemptSeq()),
                isNull(),
                eq(CancelStatus.PENDING.name()),
                isNull(),
                isNull(),
                isNull(),
                eq("VAN cancel requested")
        );
        verify(vanGateway).cancel(vanRequest);
        verify(recorder).recordCancelEvent(
                eq(PaymentEventType.CANCEL_VAN_RESULT_RECEIVED),
                eq(baseReq.posTrx()),
                eq(baseReq.originalPosTrx()),
                eq(baseReq.originalAttemptSeq()),
                eq(ResultCode.OK.name()),
                eq(CancelStatus.CANCELLED.name()),
                eq(vanResponse.vanTrxId()),
                eq(vanResponse.cancelApprovalNo()),
                isNull(),
                eq("VAN cancel result received")
        );
        verify(transactionService).finalizeCancel(prepared, vanResponse);
    }

    private PaymentAttempt originalApprovedAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                "42424242",
                "4242",
                "VISA",
                "fingerprint",
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );
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
}
