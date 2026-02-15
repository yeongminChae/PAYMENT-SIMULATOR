package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * [MyBatis Mapper]
 * - PAYMENT_ATTEMPT 관련 SQL 매핑 인터페이스
 * - Repository(포트 구현체)에서 이 Mapper를 호출해 DB 작업을 수행한다.
 * <p>
 * 주의:
 * - attempt_seq 발급은 동시성/원자성이 핵심이므로, SQL은 upsert/lock/returning 흐름으로 설계한다.
 * - insertAttempt는 validate 통과 이후에만 호출하는 것을 원칙으로 한다.
 */

@Mapper
public interface PaymentAttemptMapper {

    /**
     * 특정 posTrx(거래번호/포스TR)에 대한 "다음 attempt_seq"를 원자적으로 발급한다.
     * - row 없으면 1로 생성, 있으면 LAST_SEQ를 +1 갱신
     * - SQLite UPSERT + RETURNING 으로 DB 레벨에서 원자성 보장
     *
     * @return 발급된 attempt_seq (1,2,3...)
     */
    int insertAttemptSeq(@Param("posTrx") String posTrx);

    /**
     * 발급된 attemptSeq를 포함한 "승인 시도" 레코드를 저장한다.
     * - validate 통과 이후에만 호출하는 것을 원칙으로 한다.
     * - cardBin/cardLast4 등 민감정보 제외 최소 카드정보만 저장한다.
     * - (POS_TRX, ATTEMPT_SEQ) UNIQUE 위반 시 예외로 중복 시도를 감지한다.
     */
    void insertAttempt(@Param("attempt") AttemptInsertParam attempt);

    /**
     * 특정 posTrx(거래번호/포스TR)에 대한 결제 시도 상태를 조회한다. (A4 분기용)
     * <p>
     * 반환:
     * - Optional.empty() : 해당 posTrx로 저장된 attempt row가 아직 없음 (첫 승인 시도 전)
     * - Optional.of(...) : attempt row가 존재함
     * - finalStatus == null : 처리중 → A10(RETRY_LATER)
     * - finalStatus != null : 확정 → A9(결과 재응답)
     *
     * @return PaymentAttempt
     */
    Optional<PaymentAttempt> findLatestByPosTrx(@Param("posTrx") String posTrx);

    Optional<PaymentAttempt> findLatestByPosTrxAndAttemptSeq(@Param("posTrx") String posTrx, @Param("attemptSeq") int attemptSeq);

    /**
     * [MyBatis] 승인 결과 확정 업데이트(A7)
     * <p>
     * 의도:
     * - Service/Repository는 "최종 결과를 1회 확정한다"는 의도만 전달한다.
     * - 멱등성/동시성 보장(FINAL_STATUS IS NULL)은 SQL에서 수행한다.
     * <p>
     * 반환(Optional):
     * - Optional.of(row):
     * 이번 호출이 확정 저장에 성공했고, DB에 저장된 최종 값을 반환한다.
     * (서비스는 이 값으로 즉시 응답(A8)을 만들어도 된다.)
     * - Optional.empty():
     * (1) 이미 확정됨 (중복 요청/경합)
     * (2) 대상 row 없음 (A3 insert 실패/정합성 문제)
     * (3) attemptSeq mismatch
     * → 서비스는 이 경우 DB 재조회(findLatestByPosTrx)로 A9/A10 분기한다.
     * <p>
     * 보안:
     * - 민감정보(PAN/expiry)는 절대 다루지 않는다.
     */
    Optional<PaymentAttemptUpdatedRow> updateAttemptResult(
            @Param("attempt") AttemptResultUpdateParam attempt
    );

}