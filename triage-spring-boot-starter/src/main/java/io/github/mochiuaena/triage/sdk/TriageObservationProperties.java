package io.github.mochiuaena.triage.sdk;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("triage.sdk")
public class TriageObservationProperties {
    public enum Kind { HTTP, DATABASE, HTTP_REQUESTS }
    public record Downstream(String id, URI baseUrl) {}
    private boolean enabled;
    private Kind kind = Kind.HTTP;
    private String serviceId;
    private String downstreamId;
    private URI downstreamBaseUrl;
    private List<Downstream> downstreams = List.of();
    private String requestPathPrefix = "/api/";
    private int maxWindowMinutes = 15;
    private int capacity = 10_000;
    private boolean endpointObservations;
    private boolean responseStatusCounts;
    private boolean requestFailureCounts;
    private boolean asyncContextPropagation;
    private boolean exceptionLocations;
    private boolean sourceVersionChecks;
    private boolean jpaObservations;
    private String jpaServiceId;
    private String jpaDatabaseId;
    private String observationAccessToken;
    private String observationPreviousToken;
    private java.util.List<String> applicationPackages = java.util.List.of();

    void validate() {
        if (kind == null || !identity(serviceId) || kind == Kind.DATABASE && !identity(downstreamId)
            || kind == Kind.HTTP && downstreams.isEmpty() && !identity(downstreamId))
            throw new IllegalArgumentException("triage.sdk requires service-id, downstream-id and a valid kind");
        if (kind == Kind.HTTP_REQUESTS && (downstreamId != null || downstreamBaseUrl != null || !downstreams.isEmpty()))
            throw new IllegalArgumentException("triage.sdk HTTP_REQUESTS does not accept downstream configuration");
        if (kind == Kind.DATABASE && !downstreams.isEmpty())
            throw new IllegalArgumentException("triage.sdk DATABASE does not accept HTTP downstream origins");
        if (endpointObservations && kind == Kind.DATABASE) throw new IllegalArgumentException("triage.sdk endpoint-observations requires an HTTP request kind");
        if (responseStatusCounts && !endpointObservations) throw new IllegalArgumentException("triage.sdk response-status-counts requires endpoint-observations");
        if (requestFailureCounts && (kind != Kind.HTTP_REQUESTS || !responseStatusCounts))
            throw new IllegalArgumentException("triage.sdk request-failure-counts requires HTTP_REQUESTS and response-status-counts");
        if (asyncContextPropagation && !endpointObservations) throw new IllegalArgumentException("triage.sdk async-context-propagation requires endpoint-observations");
        if (sourceVersionChecks && !endpointObservations) throw new IllegalArgumentException("triage.sdk source-version-checks requires endpoint-observations");
        if (jpaObservations && (kind == Kind.DATABASE || !identity(jpaServiceId) || !identity(jpaDatabaseId) || jpaServiceId.equals(serviceId)))
            throw new IllegalArgumentException("triage.sdk JPA observations require HTTP kind, a separate service-id and a database-id");
        if (applicationPackages == null || applicationPackages.size() > 8 || applicationPackages.stream().anyMatch(value -> !FailureLocations.javaName(value, 160, true))
            || exceptionLocations && (!endpointObservations || applicationPackages.isEmpty()))
            throw new IllegalArgumentException("triage.sdk exception-locations requires endpoint-observations and 1..8 application-packages");
        if (maxWindowMinutes < 1 || maxWindowMinutes > 60 || capacity < 10 || capacity > 100_000)
            throw new IllegalArgumentException("triage.sdk window must be 1..60 minutes and capacity 10..100000");
        if (requestPathPrefix == null || !requestPathPrefix.matches("/(?:[a-zA-Z0-9_-]+/)+")
                || requestPathPrefix.startsWith("/triage/"))
            throw new IllegalArgumentException("triage.sdk request-path-prefix must be an API path ending with /, outside /triage/");
        if (observationAccessToken != null && !token(observationAccessToken)
            || observationPreviousToken != null && (!token(observationPreviousToken) || observationAccessToken == null
                || observationPreviousToken.equals(observationAccessToken)))
            throw new IllegalArgumentException("triage.sdk observation tokens must be distinct 32..128 character base64url values");
        if (kind == Kind.HTTP) {
            if (downstreams.isEmpty()) {
                if (!validOrigin(downstreamBaseUrl))
                    throw new IllegalArgumentException("triage.sdk downstream-base-url must be an HTTP origin without credentials or paths");
            } else {
                if (downstreamId != null || downstreamBaseUrl != null || downstreams.size() > 8)
                    throw new IllegalArgumentException("triage.sdk downstreams require 1..8 entries without legacy downstream fields");
                var ids = new HashSet<String>();
                var origins = new HashSet<String>();
                for (var downstream : downstreams) {
                    if (downstream == null || !identity(downstream.id()) || !validOrigin(downstream.baseUrl())
                        || !ids.add(downstream.id()) || !origins.add(originKey(downstream.baseUrl())))
                        throw new IllegalArgumentException("triage.sdk downstreams require distinct identities and HTTP origins");
                }
            }
        }
    }
    private boolean identity(String value) { return value != null && value.matches("[a-z][a-z0-9-]{0,63}"); }
    private boolean token(String value) { return value.matches("[A-Za-z0-9_-]{32,128}"); }
    private boolean validOrigin(URI uri) {
        return uri != null && List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
            && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
            && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
            && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
    }
    private String originKey(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port(uri);
    }
    List<Downstream> configuredDownstreams() {
        return downstreams.isEmpty() ? List.of(new Downstream(downstreamId, downstreamBaseUrl)) : downstreams;
    }
    String downstreamFor(URI uri) {
        if (uri == null || kind != Kind.HTTP) return null;
        for (var downstream : configuredDownstreams()) {
            var origin = downstream.baseUrl();
            if (origin != null && origin.getScheme().equalsIgnoreCase(uri.getScheme())
                && origin.getHost().equalsIgnoreCase(uri.getHost()) && port(origin) == port(uri)) return downstream.id();
        }
        return null;
    }
    boolean matches(URI uri) {
        return downstreamFor(uri) != null;
    }
    private int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Kind getKind() { return kind; }
    public void setKind(Kind value) { kind = value; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String value) { serviceId = value; }
    public String getDownstreamId() { return downstreamId; }
    public void setDownstreamId(String value) { downstreamId = value; }
    public URI getDownstreamBaseUrl() { return downstreamBaseUrl; }
    public void setDownstreamBaseUrl(URI value) { downstreamBaseUrl = value; }
    public List<Downstream> getDownstreams() { return downstreams; }
    public void setDownstreams(List<Downstream> values) { downstreams = values == null ? List.of() : List.copyOf(values); }
    public String getRequestPathPrefix() { return requestPathPrefix; }
    public void setRequestPathPrefix(String value) { requestPathPrefix = value; }
    public int getMaxWindowMinutes() { return maxWindowMinutes; }
    public void setMaxWindowMinutes(int value) { maxWindowMinutes = value; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int value) { capacity = value; }
    public boolean isEndpointObservations() { return endpointObservations; }
    public void setEndpointObservations(boolean value) { endpointObservations = value; }
    public boolean isResponseStatusCounts() { return responseStatusCounts; }
    public void setResponseStatusCounts(boolean value) { responseStatusCounts = value; }
    public boolean isRequestFailureCounts() { return requestFailureCounts; }
    public void setRequestFailureCounts(boolean value) { requestFailureCounts = value; }
    public boolean isAsyncContextPropagation() { return asyncContextPropagation; }
    public void setAsyncContextPropagation(boolean value) { asyncContextPropagation = value; }
    public boolean isExceptionLocations() { return exceptionLocations; }
    public boolean isSourceVersionChecks() { return sourceVersionChecks; }
    public void setSourceVersionChecks(boolean value) { sourceVersionChecks = value; }
    public boolean isJpaObservations() { return jpaObservations; }
    public void setJpaObservations(boolean value) { jpaObservations = value; }
    public String getJpaServiceId() { return jpaServiceId; }
    public void setJpaServiceId(String value) { jpaServiceId = value; }
    public String getJpaDatabaseId() { return jpaDatabaseId; }
    public void setJpaDatabaseId(String value) { jpaDatabaseId = value; }
    public String getObservationAccessToken() { return observationAccessToken; }
    public void setObservationAccessToken(String value) { observationAccessToken = value; }
    public String getObservationPreviousToken() { return observationPreviousToken; }
    public void setObservationPreviousToken(String value) { observationPreviousToken = value; }
    public void setExceptionLocations(boolean value) { exceptionLocations = value; }
    public java.util.List<String> getApplicationPackages() { return applicationPackages; }
    public void setApplicationPackages(java.util.List<String> value) { applicationPackages = value == null ? null : java.util.List.copyOf(value); }
}
