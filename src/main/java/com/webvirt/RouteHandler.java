package com.webvirt;

import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;

/**
 * Interfaz funcional para manejar rutas virtuales.
 * 
 * <h3>IMPORTANTE: Limitación de POST/PUT</h3>
 * Android WebResourceRequest no expone el body de requests POST/PUT.
 * WebVirtRequest solo puede acceder a:
 * <ul>
 *   <li>URL completa ({@link WebVirtRequest#getUrl()})</li>
 *   <li>Método HTTP ({@link WebVirtRequest#getMethod()})</li>
 *   <li>Headers ({@link WebVirtRequest#getHeaders()})</li>
 *   <li>Path y query string ({@link WebVirtRequest#getPath()}, {@link WebVirtRequest#getQuery()})</li>
 * </ul>
 * 
 * <h3>Alternativas para enviar datos desde la SPA al código nativo:</h3>
 * <ol>
 *   <li><b>Query parameters en GET:</b> {@code /api/action?param=value}</li>
 *   <li><b>JavaScript Bridge:</b> {@code window.NativeBridge.doAction(data)} — 
 *       ver {@link WebVirt#withBridge(WebVirt.JSBridge)}</li>
 *   <li><b>URL scheme personalizado:</b> requiere override adicional en WebViewClient</li>
 * </ol>
 * 
 * <h3>Ejemplo de uso:</h3>
 * <pre>
 * .route("/api/user", request -&gt;
 *     WebVirtResponses.json("{\"name\":\"Giovani\"}")
 * )
 * </pre>
 *
 * @see WebVirtRequest
 * @see WebVirtResponses
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * Maneja una petición a una ruta virtual.
     *
     * @param request Información de la petición (sin body en POST/PUT)
     * @return Respuesta HTTP (nunca null)
     * @throws Exception Si ocurre cualquier error durante el procesamiento
     */
    @NonNull
    WebResourceResponse handle(@NonNull WebVirtRequest request) throws Exception;
}