package com.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Corta la sesión de un ADMIN tras 1h de inactividad, sin afectar el timeout general
 * (más largo) que aplica al rol USUARIO. Un ADMIN puede editar config de alarmas y
 * usuarios, así que dejar esa sesión abierta indefinidamente en una PC compartida de
 * planta es un riesgo mayor que el de un rol de solo lectura.
 */
@Component
public class AdminSessionTimeoutFilter extends OncePerRequestFilter {

    private static final String ATRIBUTO_ULTIMA_ACTIVIDAD = "adminUltimaActividad";
    private static final Duration TIMEOUT_ADMIN = Duration.ofHours(1);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && esAdmin(auth)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Instant ahora = Instant.now();
                Instant ultimaActividad = (Instant) session.getAttribute(ATRIBUTO_ULTIMA_ACTIVIDAD);
                if (ultimaActividad != null && Duration.between(ultimaActividad, ahora).compareTo(TIMEOUT_ADMIN) > 0) {
                    new SecurityContextLogoutHandler().logout(request, response, auth);
                    session.invalidate();
                    filterChain.doFilter(request, response);
                    return;
                }
                session.setAttribute(ATRIBUTO_ULTIMA_ACTIVIDAD, ahora);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean esAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
