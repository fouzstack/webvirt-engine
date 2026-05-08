package com.webvirt.loader;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolvedor de tipos MIME ultra-rápido
 * Usa mapa estático para máxima velocidad
 */
class MimeTypeResolver {

    private static final Map<String, String> MIME_MAP = new HashMap<>();

    static {
        // Texto
        MIME_MAP.put("html", "text/html");
        MIME_MAP.put("htm", "text/html");
        MIME_MAP.put("css", "text/css");
        MIME_MAP.put("js", "application/javascript");
        MIME_MAP.put("mjs", "application/javascript");
        MIME_MAP.put("json", "application/json");
        MIME_MAP.put("xml", "application/xml");
        MIME_MAP.put("txt", "text/plain");
        MIME_MAP.put("md", "text/markdown");
        MIME_MAP.put("csv", "text/csv");

        // Imágenes
        MIME_MAP.put("png", "image/png");
        MIME_MAP.put("jpg", "image/jpeg");
        MIME_MAP.put("jpeg", "image/jpeg");
        MIME_MAP.put("gif", "image/gif");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("webp", "image/webp");
        MIME_MAP.put("avif", "image/avif");
        MIME_MAP.put("ico", "image/x-icon");
        MIME_MAP.put("bmp", "image/bmp");

        // Fuentes
        MIME_MAP.put("woff", "font/woff");
        MIME_MAP.put("woff2", "font/woff2");
        MIME_MAP.put("ttf", "font/ttf");
        MIME_MAP.put("otf", "font/otf");
        MIME_MAP.put("eot", "application/vnd.ms-fontobject");

        // Audio/Video
        MIME_MAP.put("mp3", "audio/mpeg");
        MIME_MAP.put("mp4", "video/mp4");
        MIME_MAP.put("webm", "video/webm");
        MIME_MAP.put("ogg", "audio/ogg");
        MIME_MAP.put("wav", "audio/wav");

        // Otros
        MIME_MAP.put("wasm", "application/wasm");
        MIME_MAP.put("pdf", "application/pdf");
        MIME_MAP.put("zip", "application/zip");
        MIME_MAP.put("map", "application/json");
        MIME_MAP.put("webmanifest", "application/manifest+json");
    }

    /**
     * Obtiene el tipo MIME según la extensión del archivo
     *
     * @param path Ruta del archivo
     * @return Tipo MIME o "application/octet-stream" si no se reconoce
     */
    static String getMimeType(String path) {
        if (path == null) return "application/octet-stream";

        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) return "application/octet-stream";

        String ext = path.substring(lastDot + 1).toLowerCase();
        return MIME_MAP.getOrDefault(ext, "application/octet-stream");
    }
}