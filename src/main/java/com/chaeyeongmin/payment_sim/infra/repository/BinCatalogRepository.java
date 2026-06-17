package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.BinCatalog;

import java.util.Optional;

public interface BinCatalogRepository {
    Optional<BinCatalog> findActiveBin8(String bin);
}
