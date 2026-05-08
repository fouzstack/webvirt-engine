package com.webvirt;

/**
 * Constantes de versión centralizadas para WebVirt.
 * 
 * Uso:
 *   - WebVirtVersion.VERSION  → "3.1.1"
 *   - WebVirtVersion.FULL     → "WebVirt v3.1.1"
 */
public final class WebVirtVersion {
    
    public static final String NAME    = "WebVirt";
    public static final String VERSION = "3.1.1";
    public static final String FULL    = NAME + " v" + VERSION;

    private WebVirtVersion() {
        throw new UnsupportedOperationException("Constants class");
    }
}