package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Records an MVC exception before the application's own resolver handles it. */
final class TriageHandledExceptionObserver implements WebMvcConfigurer, HandlerExceptionResolver {
    private final ObservationRecorder recorder;

    TriageHandledExceptionObserver(ObservationRecorder recorder) { this.recorder = recorder; }

    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, this);
    }

    @Override public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                                    Object handler, Exception error) {
        var context = TriageRequestFilter.CURRENT.get();
        if (context == null && request.getAttribute(TriageRequestFilter.CONTEXT_ATTRIBUTE) instanceof TriageRequestFilter.Context observed) context = observed;
        if (context != null) {
            var selected = context;
            selected.recordFailure(false, () -> recorder.requestFailure(error, selected.handlerClass));
        }
        return null;
    }
}
