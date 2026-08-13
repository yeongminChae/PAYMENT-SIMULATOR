package com.chaeyeongmin.van_sim.transaction.approval.service.impl;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class ApprovalServiceIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private VanApprovalRepository repository;

    @Autowired
    private ApprovalScenarioRegistry scenarioRegistry;

    @MockitoBean
    private VanTransactionIdGenerator vanTransactionIdGenerator;

    @MockitoBean
    private ApprovalNumberGenerator approvalNumberGenerator;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void APPROVED_승인_처리_결과가_PostgreSQL_원장에_저장된다() {
        // given
        ApprovalCommand command = newCommand("0001");

        ApprovalScenario scenario = new ApprovalScenario(
                IssuerResult.APPROVED,
                TransportBehavior.NORMAL
        );

        scenarioRegistry.register(command.posTrx(), scenario);

        when(vanTransactionIdGenerator.generate()).thenReturn("VAN-IT-001");
        when(approvalNumberGenerator.generate()).thenReturn("APPROVAL-IT-001");

        // when
        ApprovalResult result = approvalService.processApproval(command);

        // then
        VanApproval saved = repository.findByPosTrxAndAttemptSeq(command.posTrx(), command.attemptSeq())
                                    .orElseThrow();

        assertThat(saved.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(saved.getVanTrxId()).isEqualTo("VAN-IT-001");
        assertThat(saved.getApprovalNo()).isEqualTo("APPROVAL-IT-001");
        assertThat(result.status()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(result.vanTrxId()).isEqualTo(saved.getVanTrxId());

    }

    @Test
    void 동일한_승인_요청을_재처리해도_PostgreSQL_원장은_한_건만_유지된다() {
        // given
        ApprovalCommand command = newCommand("0002");

        ApprovalScenario scenario = new ApprovalScenario(
                IssuerResult.APPROVED,
                TransportBehavior.NORMAL
        );

        scenarioRegistry.register(command.posTrx(), scenario);

        when(vanTransactionIdGenerator.generate()).thenReturn("VAN-IT-002");
        when(approvalNumberGenerator.generate()).thenReturn("APPROVAL-IT-002");

        // when
        ApprovalResult first = approvalService.processApproval(command);
        ApprovalResult retry = approvalService.processApproval(command);

        // then
        assertThat(first.vanTrxId()).isEqualTo(retry.vanTrxId());
        assertThat(first.approvalNo()).isEqualTo(retry.approvalNo());
        assertThat(first.processedAt()).isEqualTo(retry.processedAt());

        // 실제 PostgreSQL 원장은 한 건만 존재
        assertThat(repository.count()).isEqualTo(1);

        // 재요청에서는 새 거래번호/승인번호를 생성하지 않음
        verify(vanTransactionIdGenerator, times(1)).generate();
        verify(approvalNumberGenerator, times(1)).generate();

    }

    // 테스트별 posTrx만 다르게 만들고 나머지 승인 요청값은 고정한다.
    private ApprovalCommand newCommand(String posTrxSuffix) {
        return newCommand(posTrxSuffix, 10_000);
    }

    private ApprovalCommand newCommand(String posTrxSuffix, int amount) {
        return newCommand(posTrxSuffix, amount, "12345678", "1234");
    }

    private ApprovalCommand newCommand(String posTrxSuffix, String cardBin, String cardLast4) {
        return newCommand(posTrxSuffix, 10_000, cardBin, cardLast4);
    }

    private ApprovalCommand newCommand(
            String posTrxSuffix,
            int amount,
            String cardBin,
            String cardLast4
    ) {
        return ApprovalCommand.of(
                "2301-20260808-9999-" + posTrxSuffix,
                1,
                amount,
                cardBin,
                cardLast4
        );
    }

}