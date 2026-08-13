package com.chaeyeongmin.van_sim.transaction.approval.service;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.ApprovalStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.exception.ApprovalRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.approval.service.impl.ApprovalServiceImpl;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * [Test]
 * ApprovalServiceImpl의 VAN 승인 시나리오별 원장 저장과 응답 조립을 검증한다.
 * <p>
 * 검증 포인트:
 * - APPROVED 시나리오는 승인번호를 생성하고 APPROVED 원장을 저장한다.
 * - DECLINED 시나리오는 승인번호 없이 거절 코드가 포함된 DECLINED 원장을 저장한다.
 * - ISSUER_TIMEOUT 시나리오는 승인번호/거절 코드 없이 UNKNOWN 원장을 저장한다.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceImplTest {

    @Mock
    private VanApprovalRepository repository;

    @Mock
    private ApprovalScenarioRegistry scenarioRegistry;

    @Mock
    private VanTransactionIdGenerator vanTransactionIdGenerator;

    @Mock
    private ApprovalNumberGenerator approvalNumberGenerator;

    @InjectMocks
    private ApprovalServiceImpl approvalService;

    private VanApproval savedApproval;

    @Test
    void APPROVED_시나리오이면_승인_원장을_저장하고_APPROVED를_반환한다() {

        // given: 신규 승인 요청
        ApprovalCommand command = newCommand("0001");
        givenNewApproval(command, IssuerResult.APPROVED, "VAN-TEST-001");
        givenApprovalNumber("APPROVAL-TEST-001");

        // when: 승인 요청 처리
        ApprovalResult result = approvalService.processApproval(command);

        // then: 반환된 승인 결과 검증
        assertResult(
                result,
                command,
                ApprovalStatus.APPROVED,
                "VAN-TEST-001",
                "APPROVAL-TEST-001",
                null
        );

        verify(repository).save(any(VanApproval.class));

        // 저장하려던 승인 원장 내용 검증
        assertSavedApproval(
                savedApproval,
                command,
                ApprovalStatus.APPROVED,
                "APPROVAL-TEST-001",
                null
        );
    }

    @Test
    void DECLINED_시나리오이면_승인_원장을_저장하고_DECLINED를_반환한다() {

        // given: 신규 승인 요청
        ApprovalCommand command = newCommand("0002");
        givenNewApproval(command, IssuerResult.DECLINED, "VAN-TEST-002");

        // when: 승인 요청 처리
        ApprovalResult result = approvalService.processApproval(command);

        // then: 반환된 거절 결과 검증
        assertResult(
                result,
                command,
                ApprovalStatus.DECLINED,
                "VAN-TEST-002",
                null,
                "D001"
        );

        verify(repository).save(any(VanApproval.class));

        // 저장하려던 거절 원장 내용 검증
        assertSavedApproval(
                savedApproval,
                command,
                ApprovalStatus.DECLINED,
                null,
                "D001"
        );

        // 거절 거래에서는 승인번호를 생성하지 않음
        verify(approvalNumberGenerator, never()).generate();
    }

    @Test
    void ISSUER_TIMEOUT_시나리오이면_UNKNOWN_원장을_저장하고_UNKNOWN을_반환한다() {

        // given: 신규 승인 요청
        ApprovalCommand command = newCommand("0003");
        givenNewApproval(command, IssuerResult.ISSUER_TIMEOUT, "VAN-TEST-003");

        // when: 승인 요청 처리
        ApprovalResult result = approvalService.processApproval(command);

        // then: 반환된 미확정 결과 검증
        assertResult(
                result,
                command,
                ApprovalStatus.UNKNOWN,
                "VAN-TEST-003",
                null,
                null
        );

        verify(repository).save(any(VanApproval.class));

        // 저장하려던 미확정 원장 내용 검증
        assertSavedApproval(
                savedApproval,
                command,
                ApprovalStatus.UNKNOWN,
                null,
                null
        );

        // 발급사 타임아웃 거래에서는 승인번호를 생성하지 않음
        verify(approvalNumberGenerator, never()).generate();

    }

    @Test
    void 동일한_승인_요청이_이미_처리되었으면_기존_결과를_반환한다() {
        // given: 기존 원장과 동일한 승인 요청
        ApprovalCommand command = newCommand("0004");

        LocalDateTime processedAt =
                LocalDateTime.of(2026, 8, 13, 10, 0);

        VanApproval existing = newApprovedApproval(command, processedAt);

        givenExistingApproval(command, existing);

        // when: 동일한 승인 요청 재처리
        ApprovalResult result = approvalService.processApproval(command);

        // then: 신규 처리 없이 기존 승인 결과 반환
        assertResult(
                result,
                command,
                ApprovalStatus.APPROVED,
                "VAN-ORIGINAL-001",
                "APPROVAL-ORIGINAL-001",
                null
        );

        assertThat(result.processedAt()).isEqualTo(processedAt);

        // 이미 처리된 거래이므로 신규 승인 로직을 다시 실행하지 않음
        verify(repository, never()).save(any(VanApproval.class));
        verify(scenarioRegistry, never()).find(anyString());
        verify(vanTransactionIdGenerator, never()).generate();
        verify(approvalNumberGenerator, never()).generate();

    }

    @Test
    void 동일한_거래키로_금액이_다르면_충돌로_처리한다() {
        // given: 기존에 승인 완료된 거래
        ApprovalCommand existingCommand = newCommand("0005");

        LocalDateTime processedAt =
                LocalDateTime.of(2026, 8, 13, 10, 0);

        VanApproval existing = newApprovedApproval(existingCommand, processedAt);

        // 같은 거래키지만 금액만 다르게 재요청
        ApprovalCommand conflictCommand = newCommand("0005", 20_000);

        givenExistingApproval(conflictCommand, existing);

        // when & then: 기존 원장과 요청 금액이 달라 충돌
        assertThatThrownBy(() ->
                approvalService.processApproval(conflictCommand)
        ).isInstanceOf(ApprovalRequestConflictException.class);

        // 충돌 요청은 신규 승인 처리를 진행하지 않음
        verify(repository, never()).save(any(VanApproval.class));
        verify(scenarioRegistry, never()).find(anyString());
        verify(vanTransactionIdGenerator, never()).generate();
        verify(approvalNumberGenerator, never()).generate();

    }

    @Test
    void 동일한_거래키로_cardBin이_다르면_충돌로_처리한다() {
        // given: 기존에 승인 완료된 거래
        ApprovalCommand existingCommand = newCommand("0006");

        LocalDateTime processedAt =
                LocalDateTime.of(2026, 8, 13, 10, 0);

        VanApproval existing = newApprovedApproval(existingCommand, processedAt);

        // 같은 거래키지만 카드 BIN만 다르게 재요청
        ApprovalCommand conflictCommand = newCommand("0006", "87654321", "1234");

        givenExistingApproval(conflictCommand, existing);

        // when & then: 기존 원장과 카드 BIN이 달라 충돌
        assertThatThrownBy(() ->
                approvalService.processApproval(conflictCommand)
        ).isInstanceOf(ApprovalRequestConflictException.class);

        // 충돌 요청은 신규 승인 처리를 진행하지 않음
        verify(repository, never()).save(any(VanApproval.class));
        verify(scenarioRegistry, never()).find(anyString());
        verify(vanTransactionIdGenerator, never()).generate();
        verify(approvalNumberGenerator, never()).generate();

    }

    @Test
    void 동일한_거래키로_cardLast4가_다르면_충돌로_처리한다() {
        // given: 기존에 승인 완료된 거래
        ApprovalCommand existingCommand = newCommand("0007");

        LocalDateTime processedAt =
                LocalDateTime.of(2026, 8, 13, 10, 0);

        VanApproval existing = newApprovedApproval(existingCommand, processedAt);

        // 같은 거래키지만 카드 마지막 4자리만 다르게 재요청
        ApprovalCommand conflictCommand = newCommand("0007", "12345678", "4321");

        givenExistingApproval(conflictCommand, existing);

        // when & then: 기존 원장과 카드 마지막 4자리가 달라 충돌
        assertThatThrownBy(() ->
                approvalService.processApproval(conflictCommand)
        ).isInstanceOf(ApprovalRequestConflictException.class);

        // 충돌 요청은 신규 승인 처리를 진행하지 않음
        verify(repository, never()).save(any(VanApproval.class));
        verify(scenarioRegistry, never()).find(anyString());
        verify(vanTransactionIdGenerator, never()).generate();
        verify(approvalNumberGenerator, never()).generate();

    }

    // 신규 승인 요청에서 모든 시나리오가 공통으로 필요로 하는 mock 설정을 묶는다.
    private void givenNewApproval(
            ApprovalCommand command,
            IssuerResult issuerResult,
            String vanTrxId
    ) {
        when(repository.findByPosTrxAndAttemptSeq(
                command.posTrx(),
                command.attemptSeq()
        )).thenReturn(Optional.empty());

        when(scenarioRegistry.find(command.posTrx()))
                .thenReturn(Optional.of(new ApprovalScenario(
                        issuerResult,
                        TransportBehavior.NORMAL
                )));

        when(vanTransactionIdGenerator.generate())
                .thenReturn(vanTrxId);

        // repository는 실제 DB에 저장하는 객체가 아니라 Mock 객체다.
        // 그래서 save()를 호출해도 나중에 Repository에서 다시 조회할 수 없다.
        // 대신 save()에 전달된 VanApproval을 savedApproval 필드에 직접 보관해두고,
        // 테스트 마지막에 "서비스가 저장하려던 원장 값이 맞는지" 검증한다.
        //
        // thenAnswer는 save()가 호출되는 순간 실행되는 콜백이다.
        // invocation.getArgument(0)은 save()의 첫 번째 인자, 즉 저장하려던 VanApproval 객체다.
        // return savedApproval은 실제 Repository.save()처럼 저장된 Entity를 반환하는 흉내를 낸다.
        when(repository.save(any(VanApproval.class)))
                .thenAnswer(invocation -> {
                    savedApproval = invocation.getArgument(0);
                    return savedApproval;
                });
    }

    private void givenExistingApproval(
            ApprovalCommand command,
            VanApproval existing
    ) {
        when(repository.findByPosTrxAndAttemptSeq(
                command.posTrx(),
                command.attemptSeq()
        )).thenReturn(Optional.of(existing));
    }

    // 기존에 정상 승인 완료된 VAN 원장을 만든다.
    private VanApproval newApprovedApproval(
            ApprovalCommand command,
            LocalDateTime processedAt
    ) {
        return VanApproval.builder()
                .vanTrxId("VAN-ORIGINAL-001")
                .posTrx(command.posTrx())
                .attemptSeq(command.attemptSeq())
                .amount(command.amount())
                .cardBin(command.cardBin())
                .cardLast4(command.cardLast4())
                .approvalStatus(ApprovalStatus.APPROVED)
                .approvalNo("APPROVAL-ORIGINAL-001")
                .declineCode(null)
                .processedAt(processedAt)
                .build();
    }

    // APPROVED 시나리오에서만 필요한 승인번호 생성 결과를 고정한다.
    private void givenApprovalNumber(String approvalNo) {
        when(approvalNumberGenerator.generate())
                .thenReturn(approvalNo);
    }

    // 서비스가 반환한 ApprovalResult의 공통 필드를 시나리오 기대값과 비교한다.
    private void assertResult(
            ApprovalResult result,
            ApprovalCommand command,
            ApprovalStatus status,
            String vanTrxId,
            String approvalNo,
            String declineCode
    ) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.vanTrxId()).isEqualTo(vanTrxId);
        assertThat(result.posTrx()).isEqualTo(command.posTrx());
        assertThat(result.attemptSeq()).isEqualTo(command.attemptSeq());
        assertThat(result.approvalNo()).isEqualTo(approvalNo);
        assertThat(result.declineCode()).isEqualTo(declineCode);
    }

    // Repository에 저장 요청된 VAN 승인 원장이 요청값과 시나리오 결과를 담았는지 검증한다.
    private void assertSavedApproval(
            VanApproval saved,
            ApprovalCommand command,
            ApprovalStatus status,
            String approvalNo,
            String declineCode
    ) {
        assertThat(saved.getPosTrx()).isEqualTo(command.posTrx());
        assertThat(saved.getAttemptSeq()).isEqualTo(command.attemptSeq());
        assertThat(saved.getAmount()).isEqualTo(command.amount());
        assertThat(saved.getCardBin()).isEqualTo(command.cardBin());
        assertThat(saved.getCardLast4()).isEqualTo(command.cardLast4());
        assertThat(saved.getApprovalStatus()).isEqualTo(status);
        assertThat(saved.getApprovalNo()).isEqualTo(approvalNo);
        assertThat(saved.getDeclineCode()).isEqualTo(declineCode);
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
