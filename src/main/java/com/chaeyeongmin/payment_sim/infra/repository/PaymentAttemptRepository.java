package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;

import java.util.Optional;

/**
 * [Repository 역할]
 * - 승인 시도(attempt) 발급/저장을 위한 저장소 추상화(포트)
 * - Service는 이 인터페이스만 바라보고, 구현은 infra/repository/impl 에 둔다
 * - attemptSeq는 클라이언트가 보내지 않으며, 서버가 posTrx 기준으로 발급/관리한다
 */
public interface PaymentAttemptRepository {

    /**
     * 특정 posTrx(거래번호/포스TR)에 대한 "다음 attempt_seq"를 원자적으로 발급한다.
     * - 동일 posTrx에 대해 동시 요청이 들어와도 중복되지 않도록 DB 레벨에서 증가/락(또는 upsert)로 보장한다.
     * - 반환값은 "숫자" 시퀀스(예: 1,2,3...)로 두고, 포맷팅은 Service/Policy가 책임진다.
     */
    int insertAttemptSeq(String posTrx);

    /**
     * 발급된 attemptSeq를 포함한 "승인 시도" 레코드를 저장한다.
     * - validate 통과 이후에만 호출하는 것을 원칙으로 한다.
     * - PAN 원문 대신 cardBin/cardLast4와 HMAC fingerprint를 저장한다.
     * - (POS_TRX, ATTEMPT_SEQ) UNIQUE 위반 시 예외로 중복 시도를 감지한다.
     */
    void insertAttempt(AttemptInsertParam attempt);

    /**
     * 결제 시도(PAYMENT_ATTEMPT) 조회 메소드. (A4 분기용)
     * - Service는 MyBatis/SQL 세부 구현을 몰라도 "posTrx 기준 상태 조회"만 호출하면 된다.
     * 반환 :
     * - Optional.empty() : 해당 posTrx에 대한 attempt 레코드가 없음 → A3 신규 생성 대상
     * - Optional.of(...) : 해당 posTrx에 대한 attempt 레코드가 있음 → A4 분기 대상
     */
    Optional<PaymentAttempt> findLatestByPosTrx(String posTrx);

    /**
     * 결제 시도(PAYMENT_ATTEMPT) 조회 메소드. (A7 분기용)
     * - Service는 MyBatis/SQL 세부 구현을 몰라도 "posTrx, attemptSeq 기준 상태 조회"만 호출하면 된다.
     * 반환 :
     * - Optional.empty() : 해당 posTrx에 대한 attempt 레코드가 없음 → A3 신규 생성 대상
     * - Optional.of(...) : 해당 posTrx에 대한 attempt 레코드가 있음 → A4 분기 대상
     */
    Optional<PaymentAttempt> findLatestByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    /**
     * [A7] 승인 결과 저장(최종 결과 확정)
     * <p>
     * - PAYMENT_ATTEMPT의 FINAL_STATUS가 아직 NULL(처리중)인 row에 대해서만 최종결과를 1회 확정한다.
     * - 이미 확정된 row는 업데이트하지 않는다(멱등성/중복결제 방지).
     * <p>
     * 반환(Optional):
     * - Optional.of(row): 확정 저장 성공(이번 호출이 승자) + 확정된 row 반환
     * - Optional.empty(): 이미 확정되었거나 대상 row 없음/경합/attemptSeq mismatch
     * → 서비스는 이 경우 재조회로 최종상태(A9)/처리중(A10)을 판단한다.
     */
    Optional<PaymentAttemptUpdatedRow> updateAttemptResult(AttemptResultUpdateParam param);

}
