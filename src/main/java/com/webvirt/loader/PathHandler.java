package com.webvirt.loader;

import android.content.res.AssetManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Interfaz funcional para manejar rutas de archivos
 */
@FunctionalInterface
public interface PathHandler {

    /**
     * Maneja una petición de archivo
     *
     * @param path    Ruta normalizada del archivo
     * @param request Petición original del WebView
     * @return Respuesta o null si no se encuentra
     * @throws IOException Si hay error de lectura
     */
    @Nullable
    WebResourceResponse handle(
            @NonNull String path,
            @NonNull WebResourceRequest request
    ) throws IOException;

    /**
     * Crea un handler para archivos en assets
     *
     * @param assetManager AssetManager del contexto
     * @param basePath     Subcarpeta base (ej: "www")
     */
    @NonNull
    static PathHandler fromAssets(
            @NonNull AssetManager assetManager,
            @NonNull String basePath
    ) {
        return new AssetPathHandler(assetManager, basePath);
    }

    /**
     * Crea un handler para archivos del sistema
     *
     * @param baseDir Directorio base (ej: getCacheDir())
     */
    @NonNull
    static PathHandler fromFile(@NonNull File baseDir) {
        return new FilePathHandler(baseDir);
    }
}