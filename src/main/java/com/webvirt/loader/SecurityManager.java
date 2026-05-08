package com.webvirt.loader;

import java.util.*;

/**
 * Gestor de seguridad para validación de rutas y extensiones.
 * 
 * <b>Package-private by design.</b>
 * No debe referenciarse fuera de {@code com.webvirt.loader}.
 * La configuración se expone a través de {@link WebVirtFileLoader.Builder}.
 */
class SecurityManager {

    private final Set<String> allowedExtensions;
    private final long maxFileSize;

    private static final Set<String> DEFAULT_EXTENSIONS = new HashSet<>(Arrays.asList(
        // Web
        "html", "htm", "css", "js", "mjs", "json", "xml", "txt", "md",
        // Imágenes
        "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "bmp",
        // Fuentes
        "woff", "woff2", "ttf", "otf", "eot",
        // Media
        "mp3", "mp4", "webm", "ogg", "wav",
        // Datos
        "wasm", "map", "csv", "pdf",
        // Manifiesto
        "webmanifest"
    ));

    SecurityManager(Set<String> allowedExtensions, long maxFileSize) {
        this.allowedExtensions = new HashSet<>();
        for (String ext : allowedExtensions) {
            this.allowedExtensions.add(ext.toLowerCase());
        }
        this.maxFileSize = maxFileSize;
    }

    static Set<String> getDefaultAllowedExtensions() {
        return new HashSet<>(DEFAULT_EXTENSIONS);
    }

    /**
     * Verifica si la extensión del archivo está permitida
     */
    boolean isPathAllowed(String path) {
        if (path == null) return false;

        // Bloquear path traversal
        if (path.contains("..") || path.contains("//") || path.contains("\\\\")) {
            return false;
        }

        // Permitir rutas sin extensión (SPA fallback)
        String ext = getExtension(path);
        if (ext == null) return true;

        return allowedExtensions.contains(ext.toLowerCase());
    }

    /**
     * Verifica si el tamaño está dentro del límite
     */
    boolean isSizeAllowed(long size) {
        return size > 0 && size <= maxFileSize;
    }

    private String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot + 1) : null;
    }
}