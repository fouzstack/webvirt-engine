package com.webvirt.loader;

import android.content.res.AssetManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler optimizado para archivos en assets/
 * Soporta streaming para archivos grandes
 * v3.1.1 - RangeInputStream y RangeParser externalizados
 */
class AssetPathHandler implements PathHandler {

    private final AssetManager assetManager;
    private final String basePath;
    private static final int BUFFER_SIZE = 8192;

    AssetPathHandler(@NonNull AssetManager assetManager, @NonNull String basePath) {
        this.assetManager = assetManager;
        String normalized = basePath.trim();
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }
        this.basePath = normalized;
    }

    @Nullable
    @Override
    public WebResourceResponse handle(
            @NonNull String path,
            @NonNull WebResourceRequest request
    ) throws IOException {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String fullPath = basePath + cleanPath;

        InputStream inputStream;
        try {
            inputStream = assetManager.open(fullPath);
        } catch (FileNotFoundException e) {
            return null;
        }

        // Verificar si es directorio
        try {
            if (assetManager.list(fullPath).length > 0 && !fullPath.endsWith(".html")) {
                inputStream.close();
                String indexPath = fullPath.endsWith("/") ? fullPath + "index.html" : fullPath + "/index.html";
                try {
                    inputStream = assetManager.open(indexPath);
                    fullPath = indexPath;
                } catch (IOException e) {
                    return null;
                }
            }
        } catch (IOException e) {
            // No es directorio, continuar
        }

        String mimeType = MimeTypeResolver.getMimeType(fullPath);

        if (isLargeFile(fullPath)) {
            return new WebResourceResponse(
                mimeType,
                "UTF-8",
                inputStream
            );
        }

        try {
            byte[] data = readFully(inputStream);
            return new WebResourceResponse(
                mimeType,
                "UTF-8",
                new ByteArrayInputStream(data)
            );
        } finally {
            inputStream.close();
        }
    }

    /**
     * Maneja peticiones Range para streaming parcial
     */
    @Nullable
    public WebResourceResponse handleRange(
            @NonNull String path,
            @NonNull String rangeHeader,
            @NonNull WebResourceRequest request) {

        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String fullPath = basePath + cleanPath;

        try {
            InputStream is = assetManager.open(fullPath);
            long fileSize = is.available();
            is.close();

            if (fileSize <= 0) return null;

            // M2: Usar RangeParser centralizado
            long[] range = RangeParser.parse(rangeHeader, fileSize);
            if (range == null) {
                return create416Response(fileSize);
            }

            long start = range[0];
            long end = range[1];
            int contentLength = (int) (end - start + 1);

            InputStream stream = assetManager.open(fullPath);
            long skipped = 0;
            while (skipped < start) {
                long skipResult = stream.skip(start - skipped);
                if (skipResult <= 0) {
                    stream.close();
                    throw new IOException("Could not skip to range start");
                }
                skipped += skipResult;
            }

            // M1: Usar RangeInputStream externo
            InputStream partialStream = new BufferedInputStream(
                new RangeInputStream(stream, contentLength), BUFFER_SIZE
            );

            String mimeType = MimeTypeResolver.getMimeType(fullPath);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            headers.put("Content-Length", String.valueOf(contentLength));
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Type", mimeType);
            headers.put("Cache-Control", "no-cache");

            return new WebResourceResponse(
                mimeType,
                "UTF-8",
                206,
                "Partial Content",
                headers,
                partialStream
            );

        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    private WebResourceResponse create416Response(long fileSize) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Range", "bytes */" + fileSize);
        headers.put("Content-Length", "0");

        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            416,
            "Range Not Satisfiable",
            headers,
            new ByteArrayInputStream(new byte[0])
        );
    }

    private boolean isLargeFile(String path) {
        String ext = getExtension(path);
        return ext != null && (
            ext.equals("mp4") ||
            ext.equals("webm") ||
            ext.equals("mp3") ||
            ext.equals("ogg") ||
            ext.equals("wav") ||
            ext.equals("wasm")
        );
    }

    private byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    private String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : null;
    }
}