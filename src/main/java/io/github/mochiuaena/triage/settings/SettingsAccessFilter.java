package io.github.mochiuaena.triage.settings;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.*;
import java.util.Set;

/** Guard local settings, evaluations and cancellation against cross-origin writes. */
@Component
@Order(0)
public class SettingsAccessFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getServletPath().startsWith("/api/settings")
            || request.getServletPath().startsWith("/api/evaluation") || history(request)
            || request.getServletPath().startsWith("/api/statistics") || request.getServletPath().startsWith("/api/services/status") || source(request) || cancellation(request));
    }

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
        String marker = cancellation(request) ? "X-Triage-Run" : source(request) ? "X-Triage-Source" : history(request) ? "X-Triage-History" : "X-Triage-Settings";
        if (!"GET".equals(request.getMethod()) && !"1".equals(request.getHeader(marker))) allowed = false;
        if (!allowed) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(cancellation(request) ? "{\"status\":403,\"detail\":\"取消操作仅允许从本机同源页面发起。\"}"
                : source(request) ? "{\"status\":403,\"detail\":\"源码管理仅允许从本机同源页面访问。\"}"
                : history(request) || request.getServletPath().startsWith("/api/statistics") || request.getServletPath().startsWith("/api/services/status")
                ? "{\"status\":403,\"detail\":\"历史记录与工作区仅允许从本机同源页面访问。\"}"
                : "{\"status\":403,\"detail\":\"模型设置与评测仅允许从本机同源页面访问。\"}");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
    private boolean cancellation(HttpServletRequest request) { return request.getServletPath().matches("/api/runs/[^/]+/cancel"); }
    private boolean history(HttpServletRequest request) { return request.getServletPath().startsWith("/api/history"); }
    private boolean source(HttpServletRequest request) { return request.getServletPath().startsWith("/api/source-projects"); }
}
