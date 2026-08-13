package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 HTTP 요청마다 requestId를 확보하고 MDC에 넣어 로그 추적성을 제공하는 필터다.
 * <p>
 * 요청 처리가 끝나면 MDC 값을 제거해 다음 요청 로그에 섞이지 않도록 한다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private final RequestIdProvider requestIdProvider;

    public RequestIdFilter(RequestIdProvider requestIdProvider) {
        this.requestIdProvider = requestIdProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            requestIdProvider.getOrCreate(request, response);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdProviderImpl.MDC_KEY);
        }
    }
}
