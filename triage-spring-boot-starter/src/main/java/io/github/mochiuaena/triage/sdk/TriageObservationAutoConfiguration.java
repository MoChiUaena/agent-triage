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
    @Bean TriageObservationsEndpoint triageObservationsEndpoint(ObservationRecorder recorder) {
        return new TriageObservationsEndpoint(recorder);
    }
    @Bean @ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
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
    @Bean @ConditionalOnProperty(prefix = "triage.sdk", name = "endpoint-observations", havingValue = "true")
    TriageMvcEndpoints triageMvcEndpoints(TriageObservationProperties properties) { return new TriageMvcEndpoints(properties); }
    @Bean(destroyMethod = "close") @ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "DATABASE")
    TriageJdbcObserver triageJdbcObserver(DataSource source, ObservationRecorder recorder) throws java.sql.SQLException {
        return new TriageJdbcObserver(source, recorder);
    }
}
