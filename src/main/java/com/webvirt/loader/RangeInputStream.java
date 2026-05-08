package com.webvirt.loader;

import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream que se auto-limita a un número específico de bytes.
 * 
 * Usado internamente por AssetPathHandler y FilePathHandler
 * para servir Range Requests sin cargar archivos completos en memoria.
 * 
 * Thread-safe: No (diseñado para un solo consumidor).
 */
class RangeInputStream extends InputStream {
    
    private final InputStream source;
    private long bytesRemaining;

    /**
     * @param source InputStream origen (ya posicionado en el byte inicial del rango)
     * @param length Número exacto de bytes que se podrán leer
     */
    RangeInputStream(InputStream source, long length) {
        if (source == null) throw new IllegalArgumentException("source cannot be null");
        if (length < 0) throw new IllegalArgumentException("length cannot be negative");
        this.source = source;
        this.bytesRemaining = length;
    }

    @Override
    public int read() throws IOException {
        if (bytesRemaining <= 0) return -1;
        int result = source.read();
        if (result != -1) bytesRemaining--;
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (bytesRemaining <= 0) return -1;
        int bytesToRead = (int) Math.min(len, bytesRemaining);
        int bytesRead = source.read(b, off, bytesToRead);
        if (bytesRead > 0) bytesRemaining -= bytesRead;
        return bytesRead;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(source.available(), bytesRemaining);
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}