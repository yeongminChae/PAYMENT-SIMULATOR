package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 요청/응답에서 requestId를 조회하거나 새로 발급하는 정책을 정의한다.
 */
public interface RequestIdProvider {
    String getOrCreate(HttpServletRequest request, HttpServletResponse response);
}
