package io.github.mochiuaena.triage.settings;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.*;
import java.util.Set;

/** Local settings are privileged: reject cross-origin writes and DNS-rebinding hosts. */
@Component
@Order(0)
public class SettingsAccessFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/api/settings"); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String host = request.getServerName();
        boolean allowedHost = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(java.util.Locale.ROOT));
        boolean allowed = allowedHost && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site)) allowed = false;
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!request.getScheme().equals(uri.getScheme()) || !host.equalsIgnoreCase(uri.getHost()) || request.getServerPort() != port)
                    allowed = false;
            } catch (RuntimeException e) { allowed = false; }
        }
        if (!"GET".equals(request.getMethod()) && !"1".equals(request.getHeader("X-Triage-Settings"))) allowed = false;
        if (!allowed) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"detail\":\"模型设置仅允许通过本机同源页面访问。\"}");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
