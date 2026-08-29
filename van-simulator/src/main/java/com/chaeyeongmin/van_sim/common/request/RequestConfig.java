package com.chaeyeongmin.van_sim.common.request;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 요청 추적에 필요한 공통 웹 필터를 Spring Bean으로 등록한다.
 */
@Configuration
public class RequestConfig {

    @Bean
    public RequestIdFilter requestIdFilter(RequestIdProvider requestIdProvider) {
        return new RequestIdFilter(requestIdProvider);
    }
}
