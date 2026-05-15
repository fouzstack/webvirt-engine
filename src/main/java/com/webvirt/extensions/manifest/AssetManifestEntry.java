package com.webvirt.extensions.manifest;

import androidx.annotation.Nullable;

/**
 * Entrada individual del manifiesto de assets.
 */
public class AssetManifestEntry {
    
    /** Path hasheado del asset (ej. "/app-a1b2c3d4.js") */
    public final String hashedPath;
    
    /** Hash de integridad (ej. "sha384-...") o null */
    @Nullable
    public final String integrity;
    
    /** Tamaño en bytes del asset hasheado */
    public final long size;
    
    public AssetManifestEntry(String hashedPath, @Nullable String integrity, long size) {
        this.hashedPath = hashedPath;
        this.integrity = integrity;
        this.size = size;
    }
}