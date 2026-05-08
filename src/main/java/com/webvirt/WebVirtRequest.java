package com.webvirt;

import android.net.Uri;
import android.webkit.WebResourceRequest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.*;

/**
 * Wrapper inmutable sobre WebResourceRequest
 * Proporciona acceso simplificado a los datos de la petición
 */
public class WebVirtRequest {

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final boolean isVirtualHost;

    private WebVirtRequest(
            @NonNull String url,
            @NonNull String method,
            @NonNull Map<String, String> headers,
            boolean isVirtualHost
    ) {
        this.url = url;
        this.method = method;
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        this.isVirtualHost = isVirtualHost;
    }

    /**
     * Crea un WebVirtRequest desde un WebResourceRequest
     */
    @NonNull
    static WebVirtRequest from(
            @NonNull WebResourceRequest request,
            @NonNull String virtualHost
    ) {
        String url = request.getUrl().toString();
        String host = request.getUrl().getHost();
        boolean isVirtual = virtualHost.equals(host);

        Map<String, String> headers = new HashMap<>();
        Map<String, String> reqHeaders = request.getRequestHeaders();
        if (reqHeaders != null) {
            headers.putAll(reqHeaders);
        }

        return new WebVirtRequest(
            url,
            request.getMethod() != null ? request.getMethod() : "GET",
            headers,
            isVirtual
        );
    }

    /** @return URL completa de la petición */
    @NonNull
    public String getUrl() {
        return url;
    }

    /** @return Método HTTP (GET, POST, etc.) */
    @NonNull
    public String getMethod() {
        return method;
    }

    /** @return true si la petición es al host virtual */
    public boolean isVirtualHost() {
        return isVirtualHost;
    }

    /** @return Host de la petición */
    @NonNull
    public String getHost() {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return host != null ? host : "";
    }

    /** @return Path de la URL (siempre con "/" inicial) */
    @NonNull
    public String getPath() {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        return (path == null || path.isEmpty()) ? "/" : path;
    }

    /** @return Query string (sin "?") o cadena vacía */
    @NonNull
    public String getQuery() {
        Uri uri = Uri.parse(url);
        String query = uri.getQuery();
        return query != null ? query : "";
    }

    /** @return Mapa inmutable de headers */
    @NonNull
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** @return Header específico o null */
    @Nullable
    public String getHeader(@NonNull String name) {
        return headers.get(name);
    }

    @NonNull
    @Override
    public String toString() {
        return method + " " + url + (isVirtualHost ? " [virtual]" : "");
    }
}