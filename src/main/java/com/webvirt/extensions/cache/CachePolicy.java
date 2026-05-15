package com.webvirt.extensions.cache;

/**
 * Política de caché configurable por tipo MIME.
 * 
 * Implementaciones predefinidas:
 * - CachePolicy.DEFAULT (max-age=3600 para todo)
 * - CachePolicy.SPA_IMMUTABLE (caché agresiva para assets hasheados)
 * - CachePolicy.NO_CACHE (sin caché, para APIs dinámicas)
 */
@FunctionalInterface
public interface CachePolicy {
    
    /**
     * @param mimeType Tipo MIME del asset
     * @return Valor del header Cache-Control
     */
    String getCacheControlHeader(String mimeType);
    
    /**
     * Política por defecto: 1 hora para todo.
     * Compatible con versiones anteriores.
     */
    CachePolicy DEFAULT = mimeType -> "public, max-age=3600";
    
    /**
     * Política para SPAs con assets hasheados.
     * JS/CSS/Fuentes: inmutable (1 año)
     * HTML: no-cache
     * Imágenes: 1 día
     */
    CachePolicy SPA_IMMUTABLE = mimeType -> {
        if (mimeType.contains("javascript") 
            || mimeType.contains("css") 
            || mimeType.contains("font")
            || mimeType.contains("woff")) {
            return "public, max-age=31536000, immutable";
        } else if (mimeType.contains("html")) {
            return "no-cache";
        } else if (mimeType.contains("image")) {
            return "public, max-age=86400";
        } else {
            return "public, max-age=3600";
        }
    };
    
    /**
     * Sin caché. Para contenido dinámico.
     */
    CachePolicy NO_CACHE = mimeType -> "no-store, no-cache, must-revalidate";
}