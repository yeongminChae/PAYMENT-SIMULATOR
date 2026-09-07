package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.impl.InquiryServiceImpl;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InquiryServiceImpl이 승인 원장을 조회만 하고 저장된 결과를 그대로 반환하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryServiceImplTest {

    private static final String POS_TRX = "2301-20260808-9999-0001";
    private static final String CANCEL_POS_TRX = "2301-20260808-9999-0002";
    private static final int ATTEMPT_SEQ = 1;
    private static final LocalDateTime PROCESSED_AT =
            LocalDateTime.of(2026, 8, 27, 10, 0);

    @Mock
    private VanApprovalRepository approvalRepository;

    @Mock
    private VanCancelRepository cancelRepository;

    @InjectMocks
    private InquiryServiceImpl inquiryService;

    @Test
    void APPROVED_원장이_있으면_저장된_승인_결과를_반환한다() {
        // given
        VanApproval approval = approval(
                VanApprovalStatus.APPROVED,
                "VAN-TEST-001",
                "APPROVAL-TEST-001",
                null
        );
        when(approvalRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(approval));

        // when
        Optional<ApprovalInquiryResult> optionalResult = inquiryService.inquireApproval(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(optionalResult).isPresent();
        ApprovalInquiryResult result = optionalResult.orElseThrow();
        assertThat(result.status()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(result.approvalNo()).isEqualTo("APPROVAL-TEST-001");
        assertThat(result.vanTrxId()).isEqualTo("VAN-TEST-001");
        assertThat(result.declineCode()).isNull();
        verifyNoSave();
    }

    @Test
    void DECLINED_원장이_있으면_저장된_거절_결과를_반환한다() {
        // given
        VanApproval approval = approval(
                VanApprovalStatus.DECLINED,
                "VAN-TEST-002",
                null,
                "D001"
        );
        when(approvalRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(approval));

        // when
        Optional<ApprovalInquiryResult> optionalResult = inquiryService.inquireApproval(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(optionalResult).isPresent();
        ApprovalInquiryResult result = optionalResult.orElseThrow();
        assertThat(result.status()).isEqualTo(VanApprovalStatus.DECLINED);
        assertThat(result.declineCode()).isEqualTo("D001");
        assertThat(result.approvalNo()).isNull();
        assertThat(result.vanTrxId()).isEqualTo("VAN-TEST-002");
        verifyNoSave();

    }

    @Test
    void 승인_원장이_없으면_empty를_반환한다() {
        // given
        when(approvalRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.empty());

        // when
        Optional<ApprovalInquiryResult> result = inquiryService.inquireApproval(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(result).isEmpty();
        verify(approvalRepository, never()).save(any(VanApproval.class));
    }

    @Test
    void CANCELLED_취소_원장이_있으면_저장된_결과를_반환한다() {
        VanCancel cancel = cancel(
                VanCancelStatus.CANCELLED,
                "VAN-CANCEL-TEST-001",
                "VAN-TEST-001",
                "APPROVAL-TEST-001",
                "CANCEL-APPROVAL-TEST-001",
                null
        );

        when(cancelRepository.findByCancelPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel));

        Optional<CancelInquiryResult> optionalResult =
                inquiryService.inquireCancel(CANCEL_POS_TRX);

        assertThat(optionalResult).isPresent();
        CancelInquiryResult result = optionalResult.orElseThrow();
        assertThat(result.status()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(result.vanCancelTrxId()).isEqualTo("VAN-CANCEL-TEST-001");
        assertThat(result.cancelPosTrx()).isEqualTo(CANCEL_POS_TRX);
        assertThat(result.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-TEST-001");
        assertThat(result.declineCode()).isNull();
        assertThat(result.processedAt()).isEqualTo(PROCESSED_AT);
        verifyNoSave();
    }

    @Test
    void 취소_원장이_없으면_empty를_반환한다() {
        when(cancelRepository.findByCancelPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.empty());

        Optional<CancelInquiryResult> result =
                inquiryService.inquireCancel(CANCEL_POS_TRX);

        assertThat(result).isEmpty();
    }

    private static VanApproval approval(
            VanApprovalStatus status,
            String vanTrxId,
            String approvalNo,
            String declineCode
    ) {
        return VanApproval.builder()
                .vanTrxId(vanTrxId)
                .posTrx(POS_TRX)
                .attemptSeq(ATTEMPT_SEQ)
                .amount(10_000)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(status)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .processedAt(PROCESSED_AT)
                .build();
    }

    private static VanCancel cancel(
            VanCancelStatus status,
            String vanCancelTrxId,
            String originalVanTrxId,
            String originalApprovalNo,
            String cancelApprovalNo,
            String declineCode
    ) {
        return VanCancel.builder()
                .vanCancelTrxId(vanCancelTrxId)
                .cancelPosTrx(CANCEL_POS_TRX)
                .originalPosTrx(POS_TRX)
                .originalAttemptSeq(ATTEMPT_SEQ)
                .originalVanTrxId(originalVanTrxId)
                .originalApprovalNo(originalApprovalNo)
                .amount(10_000)
                .cancelStatus(status)
                .cancelApprovalNo(cancelApprovalNo)
                .declineCode(declineCode)
                .processedAt(PROCESSED_AT)
                .build();
    }

    private static void assertStoredFields(ApprovalInquiryResult result) {
        assertThat(result.posTrx()).isEqualTo(POS_TRX);
        assertThat(result.attemptSeq()).isEqualTo(ATTEMPT_SEQ);
        assertThat(result.processedAt()).isEqualTo(PROCESSED_AT);
    }

    private void verifyNoSave() {
        verify(approvalRepository, never()).save(any(VanApproval.class));
        verify(cancelRepository, never()).save(any(VanCancel.class));
    }
}
