package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.impl.InquiryServiceImpl;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;
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
    private static final int ATTEMPT_SEQ = 1;
    private static final LocalDateTime PROCESSED_AT =
            LocalDateTime.of(2026, 8, 27, 10, 0);

    @Mock
    private VanApprovalRepository repository;

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
        when(repository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(approval));

        // when
        InquiryResult result = inquiryService.inquire(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(result.status()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(result.approvalNo()).isEqualTo("APPROVAL-TEST-001");
        assertThat(result.vanTrxId()).isEqualTo("VAN-TEST-001");
        assertThat(result.declineCode()).isNull();
        assertStoredFields(result);
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
        when(repository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(approval));

        // when
        InquiryResult result = inquiryService.inquire(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(result.status()).isEqualTo(VanApprovalStatus.DECLINED);
        assertThat(result.declineCode()).isEqualTo("D001");
        assertThat(result.approvalNo()).isNull();
        assertThat(result.vanTrxId()).isEqualTo("VAN-TEST-002");
        assertStoredFields(result);
        verifyNoSave();
    }

    @Test
    void 원장이_없으면_UNKNOWN을_반환한다() {
        // given
        when(repository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.empty());

        // when
        InquiryResult result = inquiryService.inquire(POS_TRX, ATTEMPT_SEQ);

        // then
        assertThat(result.posTrx()).isEqualTo(POS_TRX);
        assertThat(result.attemptSeq()).isEqualTo(ATTEMPT_SEQ);
        assertThat(result.status()).isEqualTo(VanApprovalStatus.UNKNOWN);
        assertThat(result.vanTrxId()).isNull();
        assertThat(result.approvalNo()).isNull();
        assertThat(result.declineCode()).isNull();
        assertThat(result.processedAt()).isNull();
        verifyNoSave();
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

    private static void assertStoredFields(InquiryResult result) {
        assertThat(result.posTrx()).isEqualTo(POS_TRX);
        assertThat(result.attemptSeq()).isEqualTo(ATTEMPT_SEQ);
        assertThat(result.processedAt()).isEqualTo(PROCESSED_AT);
    }

    private void verifyNoSave() {
        verify(repository, never()).save(any(VanApproval.class));
    }
}
