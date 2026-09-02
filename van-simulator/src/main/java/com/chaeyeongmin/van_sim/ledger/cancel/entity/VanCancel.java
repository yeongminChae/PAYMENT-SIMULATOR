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

    // VAN 내부에서 발급한 취소 거래 추적키. cancelPosTrx와 별개로 VAN 원장 식별에 사용한다.
    @Column(name = "van_cancel_trx_id", length = 50, nullable = false)
    private String vanCancelTrxId;

    // 이번 취소 요청의 POS 거래번호. 같은 값이 재요청되면 멱등성/충돌 판단 대상이 된다.
    @Column(name = "cancel_pos_trx", length = 23, nullable = false)
    private String cancelPosTrx;

    // 취소 대상 원승인의 POS 거래번호.
    @Column(name = "original_pos_trx", length = 23, nullable = false)
    private String originalPosTrx;

    // 취소 대상 원승인의 attemptSeq. originalPosTrx와 함께 원승인 1건을 식별한다.
    @Column(name = "original_attempt_seq", nullable = false)
    private int originalAttemptSeq;

    // 취소 요청 시점에 Payment가 알고 있는 원승인 VAN 거래번호. 원승인 payload 검증/추적에 사용한다.
    @Column(name = "original_van_trx_id", length = 50, nullable = false)
    private String originalVanTrxId;

    // 원승인 성공 시 발급된 승인번호. 정상 취소 요청은 이 값이 원승인 원장과 일치해야 한다.
    @Column(name = "original_approval_no", length = 20, nullable = false)
    private String originalApprovalNo;

    // 현재 정책은 전액취소만 지원하므로 원승인 금액과 동일해야 한다.
    @Column(name = "amount", nullable = false)
    private int amount;

    // VAN 취소 원장의 최종 상태. 정상 취소와 거절을 구분한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_status", length = 20, nullable = false)
    private VanCancelStatus cancelStatus;

    // 정상 취소 성공 시에만 존재한다. cancel_status=CANCELLED이면 null일 수 없다.
    @Column(name = "cancel_approval_no", length = 20)
    private String cancelApprovalNo;

    // 취소 거절 시에만 존재한다. cancel_status=CANCEL_DECLINED이면 null일 수 없다.
    @Column(name = "decline_code", length = 20)
    private String declineCode;

    // VAN 취소 원장이 확정된 시각.
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
