package com.webvirt.loader;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler para archivos del sistema de archivos
 * Usa streaming para todos los archivos
 * v3.1.1 - RangeInputStream y RangeParser externalizados
 */
class FilePathHandler implements PathHandler {

    private final File baseDir;
    private static final int BUFFER_SIZE = 8192;

    FilePathHandler(@NonNull File baseDir) {
        this.baseDir = baseDir;
    }

    @Nullable
    @Override
    public WebResourceResponse handle(
            @NonNull String path,
            @NonNull WebResourceRequest request
    ) throws IOException {
        File file = resolveFile(path);
        if (file == null) return null;

        String mimeType = MimeTypeResolver.getMimeType(file.getName());
        FileInputStream fis = new FileInputStream(file);

        return new WebResourceResponse(
            mimeType,
            "UTF-8",
            fis
        );
    }

    /**
     * Maneja peticiones Range para streaming parcial desde sistema de archivos
     */
    @Nullable
    public WebResourceResponse handleRange(
            @NonNull String path,
            @NonNull String rangeHeader,
            @NonNull WebResourceRequest request) {

        File file = resolveFile(path);
        if (file == null) return null;

        try {
            long fileSize = file.length();
            if (fileSize <= 0) return null;

            // M2: Usar RangeParser centralizado
            long[] range = RangeParser.parse(rangeHeader, fileSize);
            if (range == null) {
                return create416Response(fileSize);
            }

            long start = range[0];
            long end = range[1];
            int contentLength = (int) (end - start + 1);

            FileInputStream fis = new FileInputStream(file);
            long skipped = 0;
            while (skipped < start) {
                long skipResult = fis.skip(start - skipped);
                if (skipResult <= 0) {
                    fis.close();
                    throw new IOException("Could not skip to range start");
                }
                skipped += skipResult;
            }

            // M1: Usar RangeInputStream externo
            InputStream partialStream = new BufferedInputStream(
                new RangeInputStream(fis, contentLength), BUFFER_SIZE
            );

            String mimeType = MimeTypeResolver.getMimeType(file.getName());
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

    @Nullable
    private File resolveFile(@NonNull String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        File file = new File(baseDir, cleanPath);

        if (!file.exists() || !file.isFile()) {
            return null;
        }

        try {
            if (!file.getCanonicalPath().startsWith(baseDir.getCanonicalPath())) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }

        return file;
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
}