package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;

public interface BinCatalogService {
    CardIdentity identify(String cardBin, String cardLast4);
}
