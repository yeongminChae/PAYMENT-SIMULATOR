package com.chaeyeongmin.payment_sim.domain.model;

public record BinCatalog(
        String bin,
        String brand,
        String issuer,
        String country,
        int binLen,
        String activeYn
) {
}
