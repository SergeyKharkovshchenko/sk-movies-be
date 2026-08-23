package com.moviesApp.model;

import java.util.Map;

/**
 * Structured log entry, shared shape between BE-originated logs and FE logs forwarded
 * through {@link com.moviesApp.controllers.LogController}. Mirrors the FE's
 * StructuredLogPayload type in sk-movies-fe/src/lib/utils/logger.ts.
 */
public class StructuredLogPayload {

    private String message;
    private String origin;
    private String path;
    private String url;
    private String routeId;
    private String traceId;
    private String sessionCorrelationId;
    private Integer status;
    private String stack;
    private String userAgent;
    private Map<String, Object> context;
    private String severity;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSessionCorrelationId() {
        return sessionCorrelationId;
    }

    public void setSessionCorrelationId(String sessionCorrelationId) {
        this.sessionCorrelationId = sessionCorrelationId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}
