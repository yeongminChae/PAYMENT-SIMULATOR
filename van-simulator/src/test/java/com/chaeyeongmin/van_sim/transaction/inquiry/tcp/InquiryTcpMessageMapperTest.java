package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResultCode;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryTargetType;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class InquiryTcpMessageMapperTest {

    private static final String APPROVAL_TRX_NO = "2301-20260808-9999-0001";
    private static final String CANCEL_TRX_NO = "2301-20260808-9999-0002";
    private static final int ATTEMPT_SEQ = 1;
    private static final LocalDateTime PROCESSED_AT =
            LocalDateTime.of(2026, 9, 3, 10, 0);

    private final InquiryTcpMessageMapper mapper = new InquiryTcpMessageMapper();

    @Test
    void APPROVAL_APPROVED_원장을_SUCCESS_APPROVED_응답으로_변환한다() {
        InquiryRequestMessage request = approvalRequest();
        ApprovalInquiryResult result = approvalResult(VanApprovalStatus.APPROVED, "APPROVAL-001", null);

        InquiryResponseMessage response = mapper.toApprovalResponse(request, result);

        assertCommonSuccess(response, request, InquiryResponseStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-APPROVAL-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-001");
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void APPROVAL_UNKNOWN_원장을_SUCCESS_UNKNOWN_응답으로_변환한다() {
        InquiryRequestMessage request = approvalRequest();
        ApprovalInquiryResult result = approvalResult(VanApprovalStatus.UNKNOWN, null, null);

        InquiryResponseMessage response = mapper.toApprovalResponse(request, result);

        assertCommonSuccess(response, request, InquiryResponseStatus.UNKNOWN);
        assertThat(response.vanTrxId()).isEqualTo("VAN-APPROVAL-001");
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void APPROVAL_원장이_없으면_NOT_FOUND와_null_상태로_변환한다() {
        InquiryRequestMessage request = approvalRequest();

        InquiryResponseMessage response = mapper.notFoundResponse(request);

        assertNotFound(response, request);
    }

    @Test
    void CANCEL_CANCELLED_원장을_SUCCESS_CANCELLED_응답으로_변환한다() {
        InquiryRequestMessage request = cancelRequest();
        CancelInquiryResult result = cancelResult(
                VanCancelStatus.CANCELLED,
                "CANCEL-APPROVAL-001",
                null
        );

        InquiryResponseMessage response = mapper.toCancelResponse(request, result);

        assertCommonSuccess(response, request, InquiryResponseStatus.CANCELLED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-001");
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-001");
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void CANCEL_CANCEL_DECLINED_원장을_SUCCESS_CANCEL_DECLINED_응답으로_변환한다() {
        InquiryRequestMessage request = cancelRequest();
        CancelInquiryResult result = cancelResult(
                VanCancelStatus.CANCEL_DECLINED,
                null,
                "C001"
        );

        InquiryResponseMessage response = mapper.toCancelResponse(request, result);

        assertCommonSuccess(response, request, InquiryResponseStatus.CANCEL_DECLINED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-001");
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo("C001");
    }

    @Test
    void CANCEL_원장이_없으면_NOT_FOUND와_null_상태로_변환한다() {
        InquiryRequestMessage request = cancelRequest();

        InquiryResponseMessage response = mapper.notFoundResponse(request);

        assertNotFound(response, request);
    }

    private static InquiryRequestMessage approvalRequest() {
        return new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-001",
                InquiryTargetType.APPROVAL,
                APPROVAL_TRX_NO,
                ATTEMPT_SEQ
        );
    }

    private static InquiryRequestMessage cancelRequest() {
        return new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-002",
                InquiryTargetType.CANCEL,
                CANCEL_TRX_NO,
                null
        );
    }

    private static ApprovalInquiryResult approvalResult(
            VanApprovalStatus status,
            String approvalNo,
            String declineCode
    ) {
        return new ApprovalInquiryResult(
                "VAN-APPROVAL-001",
                APPROVAL_TRX_NO,
                ATTEMPT_SEQ,
                status,
                approvalNo,
                declineCode,
                PROCESSED_AT
        );
    }

    private static CancelInquiryResult cancelResult(
            VanCancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new CancelInquiryResult(
                "VAN-CANCEL-001",
                CANCEL_TRX_NO,
                status,
                cancelApprovalNo,
                declineCode,
                PROCESSED_AT
        );
    }

    private static void assertCommonSuccess(
            InquiryResponseMessage response,
            InquiryRequestMessage request,
            InquiryResponseStatus status
    ) {
        assertIdentity(response, request);
        assertThat(response.resultCode()).isEqualTo(InquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.respondedAt()).isNotNull();
    }

    private static void assertNotFound(
            InquiryResponseMessage response,
            InquiryRequestMessage request
    ) {
        assertIdentity(response, request);
        assertThat(response.resultCode()).isEqualTo(InquiryResultCode.NOT_FOUND);
        assertThat(response.status()).isNull();
        assertThat(response.vanTrxId()).isNull();
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        assertThat(response.respondedAt()).isNotNull();
    }

    private static void assertIdentity(
            InquiryResponseMessage response,
            InquiryRequestMessage request
    ) {
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("INQUIRY_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.targetType()).isEqualTo(request.targetType());
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isEqualTo(request.targetAttemptSeq());
    }
}
