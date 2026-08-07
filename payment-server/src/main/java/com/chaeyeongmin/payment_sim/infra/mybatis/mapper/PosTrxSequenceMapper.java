package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * PosTrxSequenceMapper
 * <p>
 * [역할]
 * - MyBatis가 호출할 Mapper 인터페이스.
 * - Java 메서드(nextSeq)와 XML SQL(id="nextSeq")을 매핑한다.
 * <p>
 * [파라미터]
 * - @Param으로 지정한 이름은 XML에서 #{storeCd} 처럼 바인딩 키로 사용된다.
 * <p>
 * [주의]
 * - 메서드 시그니처(반환 타입/파라미터)와 XML의 SQL 결과(RETURNING SEQ)가 일치해야 한다.
 */
@Mapper
public interface PosTrxSequenceMapper {

    /**
     * [역할]
     * - POS_TRX_SEQUENCE 테이블에서 해당 키(store_cd, biz_date, pos_no)의 seq를 "원자적으로 +1" 하고,
     * 증가된 seq를 반환한다.
     * <p>
     * [바인딩]
     * - XML에서 #{storeCd}, #{bizDate}, #{posNo} 로 사용된다.
     */
    long nextSeq(@Param("storeCd") String storeCd,
                 @Param("bizDate") String bizDate,
                 @Param("posNo") String posNo);

}
