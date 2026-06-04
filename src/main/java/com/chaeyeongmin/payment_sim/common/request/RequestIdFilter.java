package com.chaeyeongmin.payment_sim.common.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * [Filter]
 * 요청이 들어올 때 requestId를 생성/전파하고,
 * 응답에도 X-REQUEST-ID 헤더로 내려주는 역할.
 *
 * - MDC에 requestId를 넣기 때문에
 *   이후 Controller/Service/Repository 로그에도 requestId가 자동으로 찍힌다.
 * - 요청 처리가 끝나면 MDC를 반드시 정리한다(스레드 재사용 이슈 방지).
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
            // 스레드 재사용 대비 MDC 정리
            MDC.remove("requestId");
        }
    }

}