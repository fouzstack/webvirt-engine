package com.webvirt.extensions.manifest;

import androidx.annotation.Nullable;

/**
 * Manifiesto de assets para resolución de hashes.
 * 
 * Permite servir assets con nombres hasheados (ej. app-a1b2c3.js)
 * cuando el HTML referencia el nombre original (app.js).
 * 
 * Ejemplo de manifiesto JSON:
 * {
 *   "/app.js": { "hashedPath": "/app-a1b2c3d4.js", "integrity": "sha384-..." },
 *   "/style.css": { "hashedPath": "/style-e5f6g7h8.css", "integrity": "sha384-..." }
 * }
 */
@FunctionalInterface
public interface AssetManifest {
    
    /**
     * Resuelve un path de asset a su versión hasheada.
     * 
     * @param path Path original (ej. "/app.js")
     * @return Entry con path hasheado e integridad, o null si no está en el manifiesto
     */
    @Nullable
    AssetManifestEntry resolve(String path);
    
    /**
     * Sin manifiesto. Cero overhead.
     */
    AssetManifest NOOP = path -> null;
    
    /**
     * Carga un manifiesto desde un JSON en assets.
     * 
     * @param context Contexto Android
     * @param assetPath Ruta del JSON (ej. "webvirt-manifest.json")
     * @return AssetManifest cargado
     */
    static AssetManifest fromJson(android.content.Context context, String assetPath) {
        return new JsonAssetManifest(context, assetPath);
    }
}