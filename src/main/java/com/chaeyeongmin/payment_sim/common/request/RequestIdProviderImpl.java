package com.chaeyeongmin.payment_sim.common.request;

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
        String rid = request.getHeader(HEADER);

        if (rid == null || rid.isBlank()) {
            rid = UUID.randomUUID().toString().replace("-", "");
        }

        // MDC에 넣기 (로그 패턴에서 %X{requestId}로 사용)
        MDC.put(MDC_KEY, rid);

        // 응답에도 실어서 Postman에서 바로 확인 가능
        response.setHeader(HEADER, rid);

        return rid;
    }
}
