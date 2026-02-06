package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

}