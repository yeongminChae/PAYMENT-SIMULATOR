package com.chaeyeongmin.van_sim.common.request;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter(new RequestIdProviderImpl());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void usesRequestIdFromHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdProviderImpl.HEADER, "request-id-from-client");
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) ->
                        requestIdInChain.set(MDC.get(RequestIdProviderImpl.MDC_KEY)));

        assertThat(requestIdInChain).hasValue("request-id-from-client");
    }

    @Test
    void generatesRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) ->
                        requestIdInChain.set(MDC.get(RequestIdProviderImpl.MDC_KEY)));

        assertThat(requestIdInChain.get()).matches("[0-9a-f]{32}");
    }

    @Test
    void generatesRequestIdWhenHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdProviderImpl.HEADER, "   ");
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) ->
                        requestIdInChain.set(MDC.get(RequestIdProviderImpl.MDC_KEY)));

        assertThat(requestIdInChain.get()).matches("[0-9a-f]{32}");
    }

    @Test
    void writesSameRequestIdToResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) ->
                        requestIdInChain.set(MDC.get(RequestIdProviderImpl.MDC_KEY)));

        assertThat(response.getHeader(RequestIdProviderImpl.HEADER))
                .isEqualTo(requestIdInChain.get());
    }

    @Test
    void removesRequestIdFromMdcAfterRequestEvenWhenChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (servletRequest, servletResponse) -> {
                    assertThat(MDC.get(RequestIdProviderImpl.MDC_KEY)).isNotBlank();
                    throw new ServletException("test failure");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdProviderImpl.MDC_KEY)).isNull();
    }
}
