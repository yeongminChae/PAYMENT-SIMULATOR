package com.chaeyeongmin.payment_sim.common.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestIdFilter extends OncePerRequestFilter {

    private final RequestIdProvider requestIdProvider;

    public RequestIdFilter(RequestIdProvider requestIdProvider) {
        this.requestIdProvider = requestIdProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // TODO: X-REQUEST-ID 수신/생성 + MDC 적용(필요시)
        requestIdProvider.getOrCreate();
        filterChain.doFilter(request, response);
    }
}