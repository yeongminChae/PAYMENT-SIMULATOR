package com.chaeyeongmin.van_sim.ledger.reversal.repository;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class VanReversalRepositoryTest {

    @Autowired
    private VanReversalRepository reversalRepository;

    @Autowired
    private VanApprovalRepository approvalRepository;

    @BeforeEach
    void setUp() {
        reversalRepository.deleteAll();
        approvalRepository.deleteAll();
    }

    @Test
    void REVERSED_reversal_원장은_reversalApprovalNo가_있으면_저장된다() {
        VanReversal reversal = reversed("0001");

        VanReversal saved = reversalRepository.saveAndFlush(reversal);
        Optional<VanReversal> found = reversalRepository.findByReversalPosTrx(
                reversal.getReversalPosTrx()
        );

        assertThat(saved.getId()).isNotNull();
        assertThat(found).isPresent();
        assertThat(found.get().getReversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(found.get().getReversalApprovalNo()).isEqualTo("REV-APP-0001");
        assertThat(found.get().getDeclineCode()).isNull();
    }

    @Test
    void REVERSAL_DECLINED_reversal_원장은_declineCode가_있으면_저장된다() {
        VanReversal reversal = declined("0002", "ORIGINAL_NOT_FOUND");

        reversalRepository.saveAndFlush(reversal);
        Optional<VanReversal> found =
                reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        reversal.getOriginalPosTrx(),
                        reversal.getOriginalAttemptSeq()
                );

        assertThat(found).isPresent();
        assertThat(found.get().getReversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(found.get().getReversalApprovalNo()).isNull();
        assertThat(found.get().getDeclineCode()).isEqualTo("ORIGINAL_NOT_FOUND");
    }

    @Test
    void 동일한_reversalPosTrx를_저장하면_중복으로_실패한다() {
        reversalRepository.saveAndFlush(reversed("0003"));

        VanReversal duplicated = VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-OTHER-0003")
                .reversalPosTrx("2301-20260808-9999-R003")
                .originalPosTrx("2301-20260808-9999-O999")
                .originalAttemptSeq(1)
                .amount(10_000)
                .reversalStatus(VanReversalStatus.REVERSED)
                .reversalApprovalNo("REV-APP-O-0003")
                .declineCode(null)
                .processedAt(LocalDateTime.now())
                .build();

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> reversalRepository.saveAndFlush(duplicated)
        );

        assertThat(exception.getMessage()).contains("uk_van_reversal_reversal_pos_trx");
    }

    @Test
    void 동일한_원승인에_reversal을_두번_저장하면_중복으로_실패한다() {
        reversalRepository.saveAndFlush(reversed("0004"));

        VanReversal duplicated = VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-OTHER-0004")
                .reversalPosTrx("2301-20260808-9999-R999")
                .originalPosTrx("2301-20260808-9999-O004")
                .originalAttemptSeq(1)
                .amount(10_000)
                .reversalStatus(VanReversalStatus.REVERSED)
                .reversalApprovalNo("REV-APP-O-0004")
                .declineCode(null)
                .processedAt(LocalDateTime.now())
                .build();

        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> reversalRepository.saveAndFlush(duplicated)
        );

        assertThat(exception.getMessage()).contains("uk_van_reversal_original");
    }

    @Test
    void reversal_원장을_저장해도_원승인_approvalStatus는_변경되지_않는다() {
        VanApproval original = approval("0005", VanApprovalStatus.UNKNOWN);
        approvalRepository.saveAndFlush(original);

        reversalRepository.saveAndFlush(reversed("0005"));

        VanApproval found = approvalRepository.findByPosTrxAndAttemptSeq(
                original.getPosTrx(),
                original.getAttemptSeq()
        ).orElseThrow();
        assertThat(found.getApprovalStatus()).isEqualTo(VanApprovalStatus.UNKNOWN);
    }

    private VanReversal reversed(String suffix) {
        return VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-" + suffix)
                .reversalPosTrx("2301-20260808-9999-R" + suffix.substring(1))
                .originalPosTrx("2301-20260808-9999-O" + suffix.substring(1))
                .originalAttemptSeq(1)
                .amount(10_000)
                .reversalStatus(VanReversalStatus.REVERSED)
                .reversalApprovalNo("REV-APP-" + suffix)
                .declineCode(null)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private VanReversal declined(String suffix, String declineCode) {
        return VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-" + suffix)
                .reversalPosTrx("2301-20260808-9999-R" + suffix.substring(1))
                .originalPosTrx("2301-20260808-9999-O" + suffix.substring(1))
                .originalAttemptSeq(1)
                .amount(10_000)
                .reversalStatus(VanReversalStatus.REVERSAL_DECLINED)
                .reversalApprovalNo(null)
                .declineCode(declineCode)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private VanApproval approval(
            String suffix,
            VanApprovalStatus approvalStatus
    ) {
        return VanApproval.builder()
                .vanTrxId("VAN-APPROVAL-" + suffix)
                .posTrx("2301-20260808-9999-O" + suffix.substring(1))
                .attemptSeq(1)
                .amount(10_000)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(approvalStatus)
                .approvalNo(approvalStatus == VanApprovalStatus.APPROVED ? "APPROVAL-" + suffix : null)
                .declineCode(approvalStatus == VanApprovalStatus.DECLINED ? "05" : null)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
