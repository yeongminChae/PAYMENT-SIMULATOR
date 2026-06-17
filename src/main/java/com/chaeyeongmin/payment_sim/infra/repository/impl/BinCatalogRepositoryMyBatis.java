package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.BinCatalog;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.BinCatalogMapper;
import com.chaeyeongmin.payment_sim.infra.repository.BinCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * BIN_CATALOG MyBatis 저장소 구현.
 *
 * <p>
 * 현재 카드 식별 정책은 active 8자리 BIN만 유효하므로,
 * 조회 조건은 XML 매퍼의 findActiveBin8에 고정한다.
 */
@Repository
@RequiredArgsConstructor
public class BinCatalogRepositoryMyBatis implements BinCatalogRepository {

    private final BinCatalogMapper mapper;

    @Override
    public Optional<BinCatalog> findActiveBin8(String bin) {
        return mapper.findActiveBin8(bin);
    }
}
