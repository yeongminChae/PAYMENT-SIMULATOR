package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PosTrxSequenceMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PosTrxSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PosTrxSequenceRepositoryMyBatis
 *
 * [역할]
 * - 포스TR(거래번호) 발급에 필요한 "시퀀스 증가/조회"를 MyBatis 기반으로 수행하는 Repository 구현체.
 * - Service 계층은 DB 접근 기술(MyBatis, JPA 등)과 무관하게 PosTrxSequenceRepository 인터페이스만 의존하도록 분리한다.
 *
 * [책임 범위]
 * - (storeCd, bizDate, posNo) 조합별로 seq를 원자적으로 증가시키고 증가된 값을 반환한다.
 * - 실제 SQL은 MyBatis Mapper(XML)에 위임한다.
 */
@Repository
@RequiredArgsConstructor
public class PosTrxSequenceRepositoryMyBatis implements PosTrxSequenceRepository {

    private final PosTrxSequenceMapper mapper;

    /**
     * - (storeCd, bizDate, posNo) 기준의 POS_TRX_SEQUENCE 레코드를 upsert 하여 seq를 1 증가시키고,
     *   증가된 seq 값을 반환한다.
     */
    @Override
    public long nextSeq(String storeCd, String bizDate, String posNo) {
        return mapper.nextSeq(storeCd, bizDate, posNo);
    }
}
