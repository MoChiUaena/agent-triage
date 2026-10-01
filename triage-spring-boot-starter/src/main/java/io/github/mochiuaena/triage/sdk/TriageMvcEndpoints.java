package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.context.request.async.WebAsyncUtils;

final class TriageMvcEndpoints implements WebMvcConfigurer, HandlerInterceptor {
    private final String prefix;
    private final boolean versions;
    private final TriageObservationProperties properties;
    TriageMvcEndpoints(TriageObservationProperties properties) { this.properties = properties; prefix = properties.getRequestPathPrefix(); versions = properties.isSourceVersionChecks(); }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var context = TriageRequestFilter.CURRENT.get();
        if (context != null && context.endpoint == null) {
            try {
                var selected = MvcEndpoint.selected(request, handler, prefix, versions);
                if (selected != null) { context.endpoint = selected.endpoint(); context.handlerClass = selected.handlerClass(); }
            }
            catch (RuntimeException ignored) { /* Missing metadata must not fail a business request. */ }
        }
        if (context != null && context.isActive() && properties.isAsyncContextPropagation())
            WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(TriageCallableContext.class,
                new TriageCallableContext(TriageObservationContext.capture(), properties));
        return true;
    }
}
