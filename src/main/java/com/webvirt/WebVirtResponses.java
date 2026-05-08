package com.webvirt;

import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Factoría de respuestas HTTP para rutas virtuales
 */
public final class WebVirtResponses {

    private WebVirtResponses() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== RESPUESTAS EXITOSAS ====================

    /**
     * Crea una respuesta 200 OK genérica
     */
    @NonNull
    public static WebResourceResponse ok(
            @NonNull String mimeType,
            @NonNull byte[] data
    ) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", mimeType);
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-cache");

        return new WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            headers,
            new ByteArrayInputStream(data)
        );
    }

    /**
     * Crea una respuesta JSON 200
     */
    @NonNull
    public static WebResourceResponse json(@NonNull String json) {
        return ok("application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Crea una respuesta HTML 200
     */
    @NonNull
    public static WebResourceResponse html(@NonNull String html) {
        return ok("text/html", html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Crea una respuesta de texto plano 200
     */
    @NonNull
    public static WebResourceResponse text(@NonNull String text) {
        return ok("text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== REDIRECCIONES ====================

    /**
     * Crea una redirección 302
     */
    @NonNull
    public static WebResourceResponse redirect(@NonNull String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Location", url);

        return new WebResourceResponse(
            "text/html",
            "UTF-8",
            302,
            "Found",
            headers,
            new ByteArrayInputStream(new byte[0])
        );
    }

    // ==================== ERRORES ====================

    /**
     * Error 404 Not Found
     */
    @NonNull
    public static WebResourceResponse notFound(@NonNull String message) {
        return error(404, "Not Found", message);
    }

    /**
     * Error 403 Forbidden
     */
    @NonNull
    public static WebResourceResponse forbidden(@NonNull String message) {
        return error(403, "Forbidden", message);
    }

    /**
     * Error 500 Internal Server Error
     */
    @NonNull
    public static WebResourceResponse serverError(@NonNull String message) {
        return error(500, "Internal Server Error", message);
    }

    /**
     * Error HTTP genérico
     */
    @NonNull
    public static WebResourceResponse error(
            int code,
            @NonNull String message
    ) {
        return error(code, getStatusText(code), message);
    }

    /**
     * Error HTTP personalizado
     */
    @NonNull
    public static WebResourceResponse error(
            int code,
            @NonNull String statusText,
            @NonNull String message
    ) {
        String html = buildErrorHtml(code, statusText, message);
        byte[] data = html.getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");

        return new WebResourceResponse(
            "text/html",
            "UTF-8",
            code,
            statusText,
            headers,
            new ByteArrayInputStream(data)
        );
    }

    // ==================== INTERNO ====================

    private static String buildErrorHtml(int code, String status, String message) {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>Error " + code + " - " + escapeHtml(status) + "</title>\n" +
            "    <style>\n" +
            "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
            "        body {\n" +
            "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
            "            background: #1a1a2e;\n" +
            "            color: #eee;\n" +
            "            display: flex;\n" +
            "            align-items: center;\n" +
            "            justify-content: center;\n" +
            "            min-height: 100vh;\n" +
            "            margin: 0;\n" +
            "        }\n" +
            "        .error-container {\n" +
            "            text-align: center;\n" +
            "            padding: 2rem;\n" +
            "            max-width: 500px;\n" +
            "        }\n" +
            "        .error-code {\n" +
            "            font-size: 6rem;\n" +
            "            font-weight: 700;\n" +
            "            color: #e74c3c;\n" +
            "            line-height: 1;\n" +
            "            margin-bottom: 0.5rem;\n" +
            "        }\n" +
            "        .error-status {\n" +
            "            font-size: 1.5rem;\n" +
            "            font-weight: 600;\n" +
            "            color: #c0392b;\n" +
            "            margin-bottom: 1rem;\n" +
            "        }\n" +
            "        .error-message {\n" +
            "            font-size: 1rem;\n" +
            "            color: #999;\n" +
            "            margin-bottom: 2rem;\n" +
            "        }\n" +
            "        .error-footer {\n" +
            "            font-size: 0.8rem;\n" +
            "            color: #555;\n" +
            "        }\n" +
            "        hr {\n" +
            "            border: none;\n" +
            "            border-top: 1px solid #333;\n" +
            "            margin: 1.5rem 0;\n" +
            "        }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <div class=\"error-container\">\n" +
            "        <div class=\"error-code\">" + code + "</div>\n" +
            "        <div class=\"error-status\">" + escapeHtml(status) + "</div>\n" +
            "        <div class=\"error-message\">" + escapeHtml(message) + "</div>\n" +
            "        <hr>\n" +
            "        <div class=\"error-footer\">" + WebVirtVersion.FULL + "</div>\n" +
            "    </div>\n" +
            "</body>\n" +
            "</html>";
    }

    private static String getStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /**
     * Escapa caracteres HTML especiales.
     * Package-private para uso desde WebVirtFileLoader (M3).
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}