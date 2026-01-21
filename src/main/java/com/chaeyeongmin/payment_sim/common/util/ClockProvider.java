package com.chaeyeongmin.payment_sim.common.util;

import java.time.Instant;

public interface ClockProvider {
    Instant now();
}