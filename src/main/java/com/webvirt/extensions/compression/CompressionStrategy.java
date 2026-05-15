package com.webvirt.extensions.compression;

import androidx.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Estrategia de compresión para assets servidos por WebVirt.
 * 
 * Implementaciones incluidas:
 * - CompressionStrategy.NOOP (default, cero overhead)
 * - BrotliStrategy (requiere dependencia externa)
 * - GzipStrategy (usa java.util.zip)
 * 
 * Uso:
 * new WebVirtFileLoader.Builder(context)
 *     .setCompressionStrategy(new BrotliStrategy())
 *     .build();
 */
public interface CompressionStrategy {
    
    /**
     * @return Content-Encoding header value (ej. "br", "gzip") o null si no aplica
     */
    @Nullable
    String getContentEncoding();
    
    /**
     * Comprime un stream de datos.
     * 
     * @param path Ruta del asset (para decidir si comprimir)
     * @param rawStream Stream original sin comprimir
     * @return Stream comprimido
     * @throws IOException Si hay error de compresión
     */
    InputStream compress(String path, InputStream rawStream) throws IOException;
    
    /**
     * Sin compresión. Cero overhead.
     */
    CompressionStrategy NOOP = new CompressionStrategy() {
        @Override
        public String getContentEncoding() {
            return null;
        }
        
        @Override
        public InputStream compress(String path, InputStream rawStream) throws IOException {
            return rawStream;
        }
    };
}