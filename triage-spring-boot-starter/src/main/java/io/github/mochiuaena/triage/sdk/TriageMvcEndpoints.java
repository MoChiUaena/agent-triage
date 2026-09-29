package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

final class TriageMvcEndpoints implements WebMvcConfigurer, HandlerInterceptor {
    private final String prefix;
    private final boolean versions;
    TriageMvcEndpoints(TriageObservationProperties properties) { prefix = properties.getRequestPathPrefix(); versions = properties.isSourceVersionChecks(); }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var context = TriageRequestFilter.CURRENT.get();
        if (context != null && context.endpoint == null) {
            try { context.endpoint = MvcEndpoint.selected(request, handler, prefix, versions); }
            catch (RuntimeException ignored) { /* Missing metadata must not fail a business request. */ }
        }
        return true;
    }
}
