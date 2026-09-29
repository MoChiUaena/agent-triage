package io.github.mochiuaena.triage.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import jakarta.servlet.http.HttpServletRequest;

/** Describes Spring's selected handler, never request arguments or proof that its body ran. */
record MvcEndpoint(String id, String httpMethod, String routeTemplate, String handlerClass, String handlerMethod,
                   List<String> parameterTypes, String stage) {
    static MvcEndpoint selected(HttpServletRequest request, Object handler, String prefix) {
        if (!(handler instanceof HandlerMethod selected)) return null;
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matched instanceof String text ? text : matched instanceof PathPattern path ? path.getPatternString() : null;
        String verb = request.getMethod();
        if (route == null || !route.startsWith(prefix) || route.length() > 160 || route.codePoints().anyMatch(Character::isISOControl)
                || !Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE").contains(verb)) return null;
        var method = ClassUtils.getMostSpecificMethod(selected.getMethod(), ClassUtils.getUserClass(selected.getBeanType()));
        String owner = method.getDeclaringClass().getCanonicalName();
        if (owner == null || owner.length() > 240 || method.getName().length() > 80 || method.getParameterCount() > 8) return null;
        var parameters = Arrays.stream(method.getParameterTypes()).map(Class::getCanonicalName).toList();
        if (parameters.stream().anyMatch(value -> value == null || value.length() > 128)) return null;
        String identity = String.join("\0", verb, route, owner, method.getName(), String.join(",", parameters));
        try {
            String id = "EP-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            return new MvcEndpoint(id, verb, route, owner, method.getName(), List.copyOf(parameters), "MVC_SELECTED");
        } catch (Exception e) { throw new IllegalStateException("Cannot identify an MVC handler"); }
    }
}
