package com.chaeyeongmin.van_sim.ledger.cancel.entity;

import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
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
 * VAN이 처리한 전체취소 결과를 영속화하는 원장 엔티티다.
 *
 * cancel 거래번호와 VAN cancel 거래번호를 유니크하게 관리하고,
 * 하나의 원승인에는 하나의 전체취소 원장만 허용한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "van_cancel",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_van_cancel_van_cancel_trx_id",
                        columnNames = "van_cancel_trx_id"
                ),
                @UniqueConstraint(
                        name = "uk_van_cancel_cancel_pos_trx",
                        columnNames = "cancel_pos_trx"
                ),
                @UniqueConstraint(
                        name = "uk_van_cancel_original",
                        columnNames = {"original_pos_trx", "original_attempt_seq"}
                )
        }
)
public class VanCancel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "van_cancel_trx_id", length = 50, nullable = false)
    private String vanCancelTrxId;

    @Column(name = "cancel_pos_trx", length = 23, nullable = false)
    private String cancelPosTrx;

    @Column(name = "original_pos_trx", length = 23, nullable = false)
    private String originalPosTrx;

    @Column(name = "original_attempt_seq", nullable = false)
    private int originalAttemptSeq;

    @Column(name = "original_van_trx_id", length = 50, nullable = false)
    private String originalVanTrxId;

    @Column(name = "original_approval_no", length = 20, nullable = false)
    private String originalApprovalNo;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_status", length = 20, nullable = false)
    private VanCancelStatus cancelStatus;

    @Column(name = "cancel_approval_no", length = 20)
    private String cancelApprovalNo;

    @Column(name = "decline_code", length = 20)
    private String declineCode;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Builder
    private VanCancel(
            String vanCancelTrxId,
            String cancelPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String originalVanTrxId,
            String originalApprovalNo,
            int amount,
            VanCancelStatus cancelStatus,
            String cancelApprovalNo,
            String declineCode,
            LocalDateTime processedAt
    ) {
        this.vanCancelTrxId = vanCancelTrxId;
        this.cancelPosTrx = cancelPosTrx;
        this.originalPosTrx = originalPosTrx;
        this.originalAttemptSeq = originalAttemptSeq;
        this.originalVanTrxId = originalVanTrxId;
        this.originalApprovalNo = originalApprovalNo;
        this.amount = amount;
        this.cancelStatus = cancelStatus;
        this.cancelApprovalNo = cancelApprovalNo;
        this.declineCode = declineCode;
        this.processedAt = processedAt;
    }
}