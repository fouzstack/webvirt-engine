package com.webvirt.loader;

import androidx.annotation.Nullable;

/**
 * Parser de headers HTTP Range.
 * 
 * Centraliza la lógica de parsing para evitar duplicación
 * en WebVirtFileLoader, AssetPathHandler y FilePathHandler.
 * 
 * Soporta:
 *   - "bytes=0-1023"          → [0, 1023]
 *   - "bytes=1024-"           → [1024, fileSize-1]
 *   - "bytes=-500"            → [fileSize-500, fileSize-1]
 *   - "bytes=0-0"             → [0, 0] (primer byte)
 */
final class RangeParser {

    /**
     * Parsea un header Range HTTP/1.1.
     *
     * @param rangeHeader Valor del header, ej: "bytes=0-1023"
     * @param fileSize    Tamaño total del archivo en bytes
     * @return long[]{start, end} (inclusive) o null si:
     *         - El header es null o no empieza con "bytes="
     *         - El formato es inválido
     *         - El rango no es satisfacible (start > end)
     */
    @Nullable
    static long[] parse(String rangeHeader, long fileSize) {
        // Validación rápida
        if (rangeHeader == null || fileSize <= 0) return null;
        if (!rangeHeader.startsWith("bytes=")) return null;

        try {
            // Extraer el valor después de "bytes="
            String value = rangeHeader.substring(6).trim();
            int dashIndex = value.indexOf('-');
            if (dashIndex < 0) return null;

            String startStr = value.substring(0, dashIndex).trim();
            String endStr   = value.substring(dashIndex + 1).trim();

            long start;
            long end;

            if (startStr.isEmpty()) {
                // Sufijo: "bytes=-500" → últimos 500 bytes
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) return null;
                start = Math.max(0, fileSize - suffix);
                end   = fileSize - 1;
            } else {
                start = Long.parseLong(startStr);
                if (start < 0) start = 0;
                
                if (endStr.isEmpty()) {
                    // "bytes=1024-" → desde 1024 hasta el final
                    end = fileSize - 1;
                } else {
                    end = Long.parseLong(endStr);
                    if (end >= fileSize) end = fileSize - 1;
                }
            }

            // Validar rango resultante
            if (start > end) return null;
            if (start >= fileSize) return null;

            return new long[]{start, end};

        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RangeParser() {
        throw new UnsupportedOperationException("Utility class");
    }
}