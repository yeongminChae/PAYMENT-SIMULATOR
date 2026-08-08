package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
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
        try {
            requestIdProvider.getOrCreate(request, response);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdProviderImpl.MDC_KEY);
        }
    }
}
