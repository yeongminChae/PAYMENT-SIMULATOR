package com.chaeyeongmin.payment_sim.payment.application.card.service;

import com.chaeyeongmin.payment_sim.payment.domain.card.CardIdentity;

public interface BinCatalogService {
    CardIdentity identify(String cardBin, String cardLast4);
}
