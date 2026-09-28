package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ReversalInquiryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class PostgresReversalInquiryIntegrationTest {

    private static final String REVERSAL_POS_TRX = "2301-20260911-9999-R001";
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 9, 11, 10, 0);

    @Autowired
    private InquiryService inquiryService;

    @Autowired
    private VanReversalRepository reversalRepository;

    @BeforeEach
    void setUp() {
        reversalRepository.deleteAll();
    }

    @Test
    void reversal_Inquiry는_저장된_row를_변경하거나_새로_생성하지_않는다() {
        VanReversal stored = reversalRepository.saveAndFlush(
                VanReversal.builder()
                        .vanReversalTrxId("VAN-REVERSAL-001")
                        .reversalPosTrx(REVERSAL_POS_TRX)
                        .originalPosTrx("2301-20260911-9999-O001")
                        .originalAttemptSeq(1)
                        .amount(10_000)
                        .reversalStatus(VanReversalStatus.REVERSED)
                        .reversalApprovalNo("REV-APP-001")
                        .declineCode(null)
                        .processedAt(PROCESSED_AT)
                        .build()
        );

        ReversalInquiryResult result = inquiryService.inquireReversal(REVERSAL_POS_TRX)
                .orElseThrow();

        VanReversal reread = reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX)
                .orElseThrow();
        assertThat(result.status()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(reversalRepository.count()).isEqualTo(1);
        assertThat(reread.getId()).isEqualTo(stored.getId());
        assertThat(reread.getVanReversalTrxId()).isEqualTo("VAN-REVERSAL-001");
        assertThat(reread.getReversalPosTrx()).isEqualTo(REVERSAL_POS_TRX);
        assertThat(reread.getOriginalPosTrx()).isEqualTo("2301-20260911-9999-O001");
        assertThat(reread.getOriginalAttemptSeq()).isEqualTo(1);
        assertThat(reread.getAmount()).isEqualTo(10_000);
        assertThat(reread.getReversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(reread.getReversalApprovalNo()).isEqualTo("REV-APP-001");
        assertThat(reread.getDeclineCode()).isNull();
        assertThat(reread.getProcessedAt()).isEqualTo(PROCESSED_AT);
    }
}
