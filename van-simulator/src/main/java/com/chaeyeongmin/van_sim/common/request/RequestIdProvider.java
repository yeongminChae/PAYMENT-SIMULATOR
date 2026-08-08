package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface RequestIdProvider {
    String getOrCreate(HttpServletRequest request, HttpServletResponse response);
}
