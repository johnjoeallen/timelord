package com.timelord.controller.timezone;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestZoneResolverTest {

    private final RequestZoneResolver resolver = new RequestZoneResolver();

    @Test
    void decodesAUrlEncodedZoneIdCookie() {
        // What the client actually sends: fragments/head.html sets the
        // cookie via encodeURIComponent, since a raw zone id like
        // "Europe/Dublin" contains '/' — this is the exact value a real
        // browser produced (caught live: it was falling back to UTC
        // because this wasn't being decoded before ZoneId.of()).
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("tz", "Europe%2FDublin"));

        assertThat(resolver.resolve(request)).isEqualTo(ZoneId.of("Europe/Dublin"));
    }

    @Test
    void fallsBackToUtcWhenNoCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resolver.resolve(request)).isEqualTo(RequestZoneResolver.DEFAULT_ZONE);
    }

    @Test
    void fallsBackToUtcForAnInvalidZoneValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("tz", "not-a-real-zone"));

        assertThat(resolver.resolve(request)).isEqualTo(RequestZoneResolver.DEFAULT_ZONE);
    }

    @Test
    void fallsBackToUtcForMalformedPercentEncoding() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("tz", "Europe%"));

        assertThat(resolver.resolve(request)).isEqualTo(RequestZoneResolver.DEFAULT_ZONE);
    }
}
