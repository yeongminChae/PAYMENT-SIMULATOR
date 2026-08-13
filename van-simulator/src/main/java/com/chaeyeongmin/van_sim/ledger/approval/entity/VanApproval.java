package com.chaeyeongmin.van_sim.ledger.approval.entity;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * VAN이 처리한 승인 결과를 영속화하는 원장 엔티티다.
 * <p>
 * VAN 거래번호와 POS 거래번호/시도 순번 조합을 유니크하게 관리해 중복 승인 기록을 막는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "van_approval",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_van_approval_van_trx_id", columnNames = "van_trx_id"),
                @UniqueConstraint(
                        name = "uk_van_approval_pos_trx_attempt_seq",
                        columnNames = {"pos_trx", "attempt_seq"}
                )
        }
)
public class VanApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "van_trx_id", length = 50, nullable = false)
    private String vanTrxId;

    @Column(name = "pos_trx", length = 23, nullable = false)
    private String posTrx;

    @Column(name = "attempt_seq", nullable = false)
    private int attemptSeq;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "card_bin", length = 8, nullable = false)
    private String cardBin;

    @Column(name = "card_last4", length = 4, nullable = false)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20, nullable = false)
    private VanApprovalStatus approvalStatus;

    @Column(name = "approval_no", length = 20)
    private String approvalNo;

    @Column(name = "decline_code", length = 20)
    private String declineCode;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Builder
    private VanApproval(
            String vanTrxId,
            String posTrx,
            int attemptSeq,
            int amount,
            String cardBin,
            String cardLast4,
            VanApprovalStatus approvalStatus,
            String approvalNo,
            String declineCode,
            LocalDateTime processedAt
    ) {
        this.vanTrxId = vanTrxId;
        this.posTrx = posTrx;
        this.attemptSeq = attemptSeq;
        this.amount = amount;
        this.cardBin = cardBin;
        this.cardLast4 = cardLast4;
        this.approvalStatus = approvalStatus;
        this.approvalNo = approvalNo;
        this.declineCode = declineCode;
        this.processedAt = processedAt;
    }
}
