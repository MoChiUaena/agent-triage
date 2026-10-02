package io.github.mochiuaena.triage.sdk;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "triage.sdk", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TriageObservationProperties.class)
public class TriageObservationAutoConfiguration {
    @Bean ObservationRecorder triageObservationRecorder(TriageObservationProperties properties) {
        properties.validate();
        return new ObservationRecorder(properties);
    }
    @Bean TriageObservationsEndpoint triageObservationsEndpoint(ObservationRecorder recorder,
                                                                org.springframework.beans.factory.ObjectProvider<TriageJpaObserver> jpa,
                                                                TriageObservationProperties properties) {
        return new TriageObservationsEndpoint(recorder, jpa, properties);
    }
    @Bean @org.springframework.context.annotation.Conditional(HttpRequestMode.class)
    FilterRegistrationBean<TriageRequestFilter> triageRequestFilter(ObservationRecorder recorder, TriageObservationProperties properties) {
        var bean = new FilterRegistrationBean<>(new TriageRequestFilter(recorder));
        bean.addUrlPatterns(properties.getRequestPathPrefix() + "*");
        bean.setAsyncSupported(true);
        return bean;
    }
    @Bean @ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
    TriageRestClientCustomizer triageRestClientCustomizer(TriageObservationProperties properties) {
        return new TriageRestClientCustomizer(properties);
    }
    @Bean @ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
    TriageRestTemplateCustomizer triageRestTemplateCustomizer(TriageObservationProperties properties) {
        return new TriageRestTemplateCustomizer(properties);
    }
    @Bean @ConditionalOnProperty(prefix = "triage.sdk", name = "endpoint-observations", havingValue = "true")
    TriageMvcEndpoints triageMvcEndpoints(TriageObservationProperties properties) { return new TriageMvcEndpoints(properties); }
    @Bean @org.springframework.context.annotation.Conditional(RequestFailureCapture.class)
    TriageHandledExceptionObserver triageHandledExceptionObserver(ObservationRecorder recorder) {
        return new TriageHandledExceptionObserver(recorder);
    }
    @Bean(destroyMethod = "close") @ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "DATABASE")
    TriageJdbcObserver triageJdbcObserver(DataSource source, ObservationRecorder recorder) throws java.sql.SQLException {
        return new TriageJdbcObserver(source, recorder);
    }
    @Bean(destroyMethod = "close") @ConditionalOnProperty(prefix = "triage.sdk", name = "jpa-observations", havingValue = "true")
    TriageJpaObserver triageJpaObserver(TriageObservationProperties properties) {
        return new TriageJpaObserver(properties);
    }
    static final class HttpRequestMode implements org.springframework.context.annotation.Condition {
        @Override public boolean matches(org.springframework.context.annotation.ConditionContext context,
                                          org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            var kind = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment())
                .bind("triage.sdk.kind", TriageObservationProperties.Kind.class).orElse(TriageObservationProperties.Kind.HTTP);
            return kind != TriageObservationProperties.Kind.DATABASE;
        }
    }
    static final class RequestFailureCapture implements org.springframework.context.annotation.Condition {
        @Override public boolean matches(org.springframework.context.annotation.ConditionContext context,
                                          org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
            return binder.bind("triage.sdk.exception-locations", Boolean.class).orElse(false)
                || binder.bind("triage.sdk.request-failure-counts", Boolean.class).orElse(false);
        }
    }
}
