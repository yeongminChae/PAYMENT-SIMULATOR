package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.BinCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface BinCatalogMapper {
    Optional<BinCatalog> findActiveBin8(@Param("bin") String bin);
}
