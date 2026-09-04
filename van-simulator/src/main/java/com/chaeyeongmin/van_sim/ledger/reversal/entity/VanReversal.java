package com.chaeyeongmin.van_sim.ledger.reversal.entity;

import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
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
 * VAN이 처리한 reversal 결과를 영속화하는 원장 엔티티다.
 *
 * <p>
 * Reversal은 정상 취소와 다른 장애 복구 거래이므로 van_cancel에 기록하지 않는다.
 * 원승인 van_approval row도 수정하지 않고, 이 테이블에 독립적인 reversal 사실만 남긴다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "van_reversal",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_van_reversal_van_reversal_trx_id",
                        columnNames = "van_reversal_trx_id"
                ),
                @UniqueConstraint(
                        name = "uk_van_reversal_reversal_pos_trx",
                        columnNames = "reversal_pos_trx"
                ),
                @UniqueConstraint(
                        name = "uk_van_reversal_original",
                        columnNames = {"original_pos_trx", "original_attempt_seq"}
                )
        }
)
public class VanReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // VAN 내부에서 발급한 reversal 거래 추적키.
    @Column(name = "van_reversal_trx_id", length = 50, nullable = false)
    private String vanReversalTrxId;

    // 이번 reversal 요청의 POS 거래번호. 같은 값은 replay/충돌 판단 대상이다.
    @Column(name = "reversal_pos_trx", length = 23, nullable = false)
    private String reversalPosTrx;

    // reversal 대상 원승인의 POS 거래번호.
    @Column(name = "original_pos_trx", length = 23, nullable = false)
    private String originalPosTrx;

    // reversal 대상 원승인의 attemptSeq. originalPosTrx와 함께 원승인 1건을 식별한다.
    @Column(name = "original_attempt_seq", nullable = false)
    private int originalAttemptSeq;

    // 현재 reversal은 전액 복구만 다루므로 원승인 금액과 동일해야 한다.
    @Column(name = "amount", nullable = false)
    private int amount;

    // VAN reversal 원장의 최종 상태.
    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_status", length = 30, nullable = false)
    private VanReversalStatus reversalStatus;

    // reversal 성공 시에만 존재한다.
    @Column(name = "reversal_approval_no", length = 20)
    private String reversalApprovalNo;

    // reversal 거절 시에만 존재한다.
    @Column(name = "decline_code", length = 30)
    private String declineCode;

    // VAN reversal 원장이 확정된 시각.
    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Builder
    private VanReversal(
            String vanReversalTrxId,
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            int amount,
            VanReversalStatus reversalStatus,
            String reversalApprovalNo,
            String declineCode,
            LocalDateTime processedAt
    ) {
        // Entity 생성은 builder로만 열어 테스트와 service가 같은 필드 계약을 사용하게 한다.
        // 상태별 payload 제약은 schema-postgres.sql의 check constraint와 함께 지켜진다.
        this.vanReversalTrxId = vanReversalTrxId;
        this.reversalPosTrx = reversalPosTrx;
        this.originalPosTrx = originalPosTrx;
        this.originalAttemptSeq = originalAttemptSeq;
        this.amount = amount;
        this.reversalStatus = reversalStatus;
        this.reversalApprovalNo = reversalApprovalNo;
        this.declineCode = declineCode;
        this.processedAt = processedAt;
    }
}
