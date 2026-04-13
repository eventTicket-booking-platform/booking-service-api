package com.ec7205.event_hub.booking_service_api.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record AuthenticatedUser(String userId, String role) {

    public static AuthenticatedUser from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
            throw new IllegalStateException("Authenticated JWT principal is required");
        }

        Jwt jwt = jwtAuthenticationToken.getToken();
        String role = jwtAuthenticationToken.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(AuthenticatedUser::normalizeRole)
                .findFirst()
                .orElse("USER");

        return new AuthenticatedUser(jwt.getSubject(), role);
    }

    private static String normalizeRole(String authority) {
        if (authority == null || authority.isBlank()) {
            return "USER";
        }
        return authority.startsWith("ROLE_") ? authority.substring(5) : authority.toUpperCase();
    }
}
