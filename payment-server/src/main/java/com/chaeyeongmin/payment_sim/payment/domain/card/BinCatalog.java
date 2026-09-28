package com.chaeyeongmin.payment_sim.payment.domain.card;

public record BinCatalog(
        String bin,
        String brand,
        String issuer,
        String country,
        int binLen,
        String activeYn
) {
}
