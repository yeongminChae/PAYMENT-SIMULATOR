package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * X-REQUEST-ID 헤더 기반 requestId 제공자다.
 * <p>
 * 클라이언트가 값을 보내면 재사용하고, 없으면 UUID 기반 값을 생성해 응답 헤더와 MDC에 기록한다.
 */
@Component
public class RequestIdProviderImpl implements RequestIdProvider {

    public static final String HEADER = "X-REQUEST-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    public String getOrCreate(HttpServletRequest request, HttpServletResponse response) {
        String requestId = request.getHeader(HEADER);

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        return requestId;
    }
}
