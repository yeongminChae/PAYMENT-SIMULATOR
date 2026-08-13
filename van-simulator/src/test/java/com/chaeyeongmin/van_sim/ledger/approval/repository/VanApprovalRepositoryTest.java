package com.chaeyeongmin.van_sim.ledger.approval.repository;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class VanApprovalRepositoryTest {
    @Autowired
    private VanApprovalRepository repository;

    @Test
    void APPROVED_승인_원장은_approvalNo가_있으면_저장된다() {
        // given
        VanApproval approval = approval(
                "0001",
                VanApprovalStatus.APPROVED,
                "APPROVAL-0001",
                null
        );

        // when
        VanApproval savedApproval = repository.saveAndFlush(approval);
        Optional<VanApproval> findApproval = repository.findByPosTrxAndAttemptSeq(
                "2301-20260808-9999-0001", 1
        );

        // then
        assertNotNull(savedApproval.getId());
        assertTrue(findApproval.isPresent());
        assertEquals("APPROVAL-0001", findApproval.get().getApprovalNo());
        assertEquals(VanApprovalStatus.APPROVED, findApproval.get().getApprovalStatus());
        assertNull(findApproval.get().getDeclineCode());
        assertEquals(savedApproval.getId(), findApproval.get().getId());
    }

    @Test
    void DECLINED_승인_원장은_declineCode가_있으면_저장된다() {
        // given
        VanApproval decline = approval(
                "0002",
                VanApprovalStatus.DECLINED,
                null,
                "01"
        );

        // when
        VanApproval savedApproval = repository.saveAndFlush(decline);
        Optional<VanApproval> findApproval = repository.findByPosTrxAndAttemptSeq(
                "2301-20260808-9999-0002", 1
        );

        // then
        assertNotNull(savedApproval.getId());
        assertTrue(findApproval.isPresent());
        assertEquals(VanApprovalStatus.DECLINED, findApproval.get().getApprovalStatus());
        assertNull(findApproval.get().getApprovalNo());
        assertEquals("01", findApproval.get().getDeclineCode());
        assertEquals(savedApproval.getId(), findApproval.get().getId());
    }

    @Test
    void UNKNOWN_승인_원장은_approvalNo와_declineCode가_없으면_저장된다() {
        // given
        VanApproval unknown = approval(
                "0003",
                VanApprovalStatus.UNKNOWN,
                null,
                null
        );

        // when
        VanApproval savedApproval = repository.saveAndFlush(unknown);
        Optional<VanApproval> findApproval = repository.findByPosTrxAndAttemptSeq(
                "2301-20260808-9999-0003", 1
        );

        // then
        assertNotNull(savedApproval.getId());
        assertTrue(findApproval.isPresent());
        assertEquals(VanApprovalStatus.UNKNOWN, findApproval.get().getApprovalStatus());
        assertNull(findApproval.get().getApprovalNo());
        assertNull(findApproval.get().getDeclineCode());
        assertEquals(savedApproval.getId(), findApproval.get().getId());
    }

    @Test
    void 동일한_posTrx와_attemptSeq를_저장하면_중복으로_실패한다() {
        // given
        VanApproval first = approval(
                "VAN-TRX-0400",
                "2301-20260808-9999-0004",
                1,
                10000,
                VanApprovalStatus.APPROVED,
                "APPROVAL-0400",
                null
        );

        repository.saveAndFlush(first);

        // when & then
        VanApproval second = approval(
                "VAN-TRX-0401",
                "2301-20260808-9999-0004",
                1,
                10000,
                VanApprovalStatus.APPROVED,
                "APPROVAL-0401",
                null
        );

        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(second)
                );

        assertThat(exception.getMessage()).contains("uk_van_approval_pos_trx_attempt_seq");

    }

    @Test
    void 동일한_vanTrxId를_저장하면_중복으로_실패한다() {
        // given
        VanApproval first = approval(
                "VAN-TRX-0005",
                "2301-20260808-9999-5000",
                1,
                10000,
                VanApprovalStatus.APPROVED,
                "APPROVAL-5000",
                null
        );

        repository.saveAndFlush(first);

        // when & then
        VanApproval second = approval(
                "VAN-TRX-0005",
                "2301-20260808-9999-5001",
                2,
                10000,
                VanApprovalStatus.APPROVED,
                "APPROVAL-5001",
                null
        );

        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(second)
                );

        assertThat(exception.getMessage()).contains("uk_van_approval_van_trx_id");

    }

    @Test
    void APPROVED_승인_원장은_approvalNo가_없으면_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0006",
                VanApprovalStatus.APPROVED,
                null,
                null
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertEquals(VanApprovalStatus.APPROVED, first.getApprovalStatus());
        assertThat(exception.getMessage()).contains("ck_van_approval_status_payload");

    }

    @Test
    void DECLINED_승인_원장은_declineCode가_없으면_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0007",
                VanApprovalStatus.DECLINED,
                null,
                null
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertEquals(VanApprovalStatus.DECLINED, first.getApprovalStatus());
        assertThat(exception.getMessage()).contains("ck_van_approval_status_payload");
    }

    @Test
    void UNKNOWN_승인_원장은_approvalNo가_있으면_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0080",
                VanApprovalStatus.UNKNOWN,
                "APPROVAL-0080",
                null
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertEquals(VanApprovalStatus.UNKNOWN, first.getApprovalStatus());
        assertThat(exception.getMessage()).contains("ck_van_approval_status_payload");
    }

    @Test
    void UNKNOWN_승인_원장은_declineCode가_있으면_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0081",
                VanApprovalStatus.UNKNOWN,
                null,
                "01"
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertEquals(VanApprovalStatus.UNKNOWN, first.getApprovalStatus());
        assertThat(exception.getMessage()).contains("ck_van_approval_status_payload");

    }

    @Test
    void amount가_0이면_승인_원장_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0009",
                1,
                0,
                VanApprovalStatus.APPROVED,
                "APPROVAL-0009",
                null
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertThat(exception.getMessage()).contains("ck_van_approval_amount_positive");
    }

    @Test
    void attemptSeq가_0이면_승인_원장_저장에_실패한다() {
        // given
        VanApproval first = approval(
                "0010",
                0,
                10000,
                VanApprovalStatus.APPROVED,
                "APPROVAL-0010",
                null
        );

        // when & then
        DataIntegrityViolationException exception =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.saveAndFlush(first)
                );

        assertThat(exception.getMessage()).contains("ck_van_approval_attempt_seq_positive");
    }

    private VanApproval approval(
            String suffix,
            VanApprovalStatus approvalStatus,
            String approvalNo,
            String declineCode
    ) {
        return approval(suffix, 1, 10000, approvalStatus, approvalNo, declineCode);
    }

    private VanApproval approval(
            String suffix,
            int attemptSeq,
            int amount,
            VanApprovalStatus approvalStatus,
            String approvalNo,
            String declineCode
    ) {
        return approval(
                "VAN-TRX-" + suffix,
                "2301-20260808-9999-" + suffix,
                attemptSeq,
                amount,
                approvalStatus,
                approvalNo,
                declineCode
        );
    }

    private VanApproval approval(
            String vanTrxId,
            String posTrx,
            int attemptSeq,
            int amount,
            VanApprovalStatus approvalStatus,
            String approvalNo,
            String declineCode
    ) {
        return VanApproval.builder()
                .vanTrxId(vanTrxId)
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .amount(amount)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(approvalStatus)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .processedAt(LocalDateTime.now())
                .build();
    }

}
