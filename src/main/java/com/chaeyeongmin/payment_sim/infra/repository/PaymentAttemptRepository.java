package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;

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
     * - cardBin/cardLast4 등 민감정보 제외 최소 카드정보만 저장한다.
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

}
