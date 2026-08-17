package com.timelord.controller.timezone;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the browser's local timezone for rendering, from a {@code tz}
 * cookie set client-side (see {@code fragments/head.html}) to the IANA zone
 * name the browser reports via {@code Intl.DateTimeFormat().resolvedOptions().timeZone}.
 *
 * Falls back to UTC when the cookie is absent (first-ever request, before
 * the client-side detection script has had a chance to set it) or contains
 * a value that isn't a valid zone id.
 */
@Component
public class RequestZoneResolver {

    private static final Logger log = LoggerFactory.getLogger(RequestZoneResolver.class);

    public static final String COOKIE_NAME = "tz";
    public static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;

    public ZoneId resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return DEFAULT_ZONE;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                try {
                    // The client sets this via encodeURIComponent (zone ids
                    // like "Europe/Dublin" contain '/', which cookie values
                    // can't hold literally) — servlet containers don't
                    // decode cookie values themselves, so this has to.
                    String decoded = URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
                    return ZoneId.of(decoded);
                } catch (DateTimeException | NullPointerException | IllegalArgumentException ex) {
                    log.debug("Ignoring invalid '{}' cookie value '{}'", COOKIE_NAME, cookie.getValue());
                }
            }
        }
        return DEFAULT_ZONE;
    }
}
