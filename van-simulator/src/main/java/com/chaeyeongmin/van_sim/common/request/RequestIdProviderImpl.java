package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
