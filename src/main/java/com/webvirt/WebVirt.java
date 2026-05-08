package com.webvirt;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// [DEV] Descomentar para activar métricas:
// import com.fouzstack.jsinterface.managers.WebVirtMetrics;
import com.webvirt.loader.WebVirtFileLoader;
import com.webvirt.loader.PathHandler;

import java.io.ByteArrayInputStream;
import java.util.*;

/**
* WebVirt v3.1.1 — Hybrid Web Runtime Engine for Android
* Powered by WebVirtFileLoader
*
* Production Hardened:
* - Lifecycle awareness (onTrimMemory, destroy)
* - CSP configurable
* - Range requests para streaming
* - Control de memoria real
* - ETag/304 soportado
* - Navegación SPA con reinicio de callbacks
* - Métricas opcionales vía WebVirtMetrics (solo si ENABLED=true)
*
* @version 3.1.1
*/
public class WebVirt {
	private static final String TAG = "WebVirt";
	
	// ==================== ESTADO ====================
	private final Context context;
	private String host;
	private String assetSubfolder = "";
	private boolean debugMode = false;
	private boolean offlineOnly = false;
	private long maxFileSize = 10 * 1024 * 1024; // 10MB por archivo
	private long maxCacheSize = 50 * 1024 * 1024; // 50MB caché total
	private int cacheEntries = 200;
	private String cspPolicy = "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; " +
	"img-src 'self' data: blob:; " +
	"media-src 'self' data: blob:; " +
	"font-src 'self' data:;";
	
	private WebView webView;
	private WebVirtFileLoader fileLoader;
	
	private final Map<String, RouteHandler> routes = new LinkedHashMap<>();
	private final Set<String> allowedDomains = new HashSet<>();
	
	private WebVirtPageListener pageListener;
	private JSBridge jsBridge;
	
	// ==================== CONSTRUCTOR ====================
	private WebVirt(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}
	
	/**
	* Punto de entrada principal
	* @param context Contexto de la aplicación (no Activity)
	* @return Instancia de WebVirt para configuración fluida
	*/
	@NonNull
	public static WebVirt with(@NonNull Context context) {
		return new WebVirt(context);
	}
	
	// ==================== CONFIGURACIÓN FLUIDA ====================
	
	/**
	* Define el host virtual (obligatorio)
	* @param host ej: "app.local"
	* @return this para encadenamiento
	* @throws IllegalArgumentException si host es null o vacío
	*/
	public WebVirt host(@NonNull String host) {
		if (host == null || host.trim().isEmpty()) {
			throw new IllegalArgumentException("Host cannot be null or empty");
		}
		this.host = host.trim().toLowerCase();
		return this;
	}
	
	/**
	* Subcarpeta dentro de assets/ donde está la SPA
	* @param subfolder ej: "www" para assets/www/
	* @return this para encadenamiento
	*/
	public WebVirt subfolder(@NonNull String subfolder) {
		this.assetSubfolder = subfolder.trim().replaceAll("^/+|/+$", "");
		return this;
	}
	
	/**
	* Activar logs de depuración
	* @param debug true para ver logs detallados
	* @return this para encadenamiento
	*/
	public WebVirt debug(boolean debug) {
		this.debugMode = debug;
		return this;
	}
	
	/**
	* Modo offline: bloquea todas las peticiones externas
	* @param offline true para modo sin conexión
	* @return this para encadenamiento
	*/
	public WebVirt offlineOnly(boolean offline) {
		this.offlineOnly = offline;
		return this;
	}
	
	/**
	* Tamaño máximo de archivo a cachear en bytes
	* @param bytes default 10MB (10 * 1024 * 1024)
	* @return this para encadenamiento
	*/
	public WebVirt maxFileSize(long bytes) {
		if (bytes > 0) {
			this.maxFileSize = bytes;
		}
		return this;
	}
	
	/**
	* Tamaño máximo total del caché en bytes
	* @param bytes default 50MB (50 * 1024 * 1024)
	* @return this para encadenamiento
	*/
	public WebVirt maxCacheSize(long bytes) {
		if (bytes > 0) {
			this.maxCacheSize = bytes;
		}
		return this;
	}
	
	/**
	* Número máximo de entradas en caché
	* @param entries default 200
	* @return this para encadenamiento
	*/
	public WebVirt cacheEntries(int entries) {
		if (entries > 0) {
			this.cacheEntries = entries;
		}
		return this;
	}
	
	/**
	* Política de Content Security Policy personalizada
	* @param csp Política CSP completa
	* @return this para encadenamiento
	*/
	public WebVirt cspPolicy(@NonNull String csp) {
		if (csp != null && !csp.trim().isEmpty()) {
			this.cspPolicy = csp.trim();
		}
		return this;
	}
	
	// ==================== RUTAS VIRTUALES ====================
	
	/**
	* Define una ruta virtual que se procesa antes que los assets
	* Perfecto para APIs falsas, configuración dinámica, etc.
	*
	* @param path Ruta ej: "/api/user"
	* @param handler Función que genera la respuesta
	* @return this para encadenamiento
	*/
	public WebVirt route(@NonNull String path, @NonNull RouteHandler handler) {
		routes.put(normalizePath(path), handler);
		return this;
	}
	
	/**
	* Permite un dominio externo adicional al host virtual.
	*
	* COMPORTAMIENTO DE SEGURIDAD:
	*   - offlineOnly=true  → bloquea TODO lo externo (ignora allowDomain)
	*   - offlineOnly=false + sin allowDomain() → permite CUALQUIER dominio externo
	*   - offlineOnly=false + con allowDomain() → solo permite los dominios listados
	*
	* Si quieres bloquear todos los externos excepto los que declares,
	* asegúrate de llamar allowDomain() al menos una vez.
	*
	* @param domain ej: "api.miservidor.com"
	* @return this para encadenamiento
	*/
	public WebVirt allowDomain(@NonNull String domain) {
		allowedDomains.add(domain.toLowerCase().trim());
		return this;
	}
	
	/**
	* Permite múltiples dominios externos
	*
	* @param domains Lista de dominios
	* @return this para encadenamiento
	*/
	public WebVirt allowDomains(@NonNull String... domains) {
		for (String domain : domains) {
			allowDomain(domain);
		}
		return this;
	}
	
	// ==================== CALLBACKS ====================
	
	/**
	* Callback cuando la SPA ha terminado de cargar completamente
	* Se ejecuta en cada navegación exitosa, no solo la primera
	*
	* @param listener Callback con el WebView listo
	* @return this para encadenamiento
	*/
	public WebVirt onPageReady(@NonNull WebVirtPageListener listener) {
		this.pageListener = listener;
		return this;
	}
	
	/**
	* Inyecta un puente JavaScript nativo después de cargar la página
	* Se ejecuta automáticamente en onPageFinished
	*
	* @param bridge Implementación del puente JS
	* @return this para encadenamiento
	*/
	public WebVirt withBridge(@NonNull JSBridge bridge) {
		this.jsBridge = bridge;
		return this;
	}
	
	// ==================== BIND ====================
	
	/**
	* Vincula WebVirt al WebView y aplica toda la configuración
	* Este método debe llamarse después de configurar todo
	*
	* @param webView WebView a configurar
	* @return this para encadenamiento
	* @throws IllegalStateException si no se definió host
	*/
	@NonNull
	public WebVirt bind(@NonNull WebView webView) {
		if (host == null || host.isEmpty()) {
			throw new IllegalStateException(
			"Host is required. Call host(\"app.local\") before bind()"
			);
		}
		
		this.webView = webView;
		
		// Crear WebVirtFileLoader con configuración completa
		fileLoader = new WebVirtFileLoader.Builder(context)
		.setDomain(host)
		.setDebugMode(debugMode)
		.setCacheEntries(cacheEntries)
		.setMaxFileSize(maxFileSize)
		.setMaxCacheSize(maxCacheSize)
		.setCspPolicy(cspPolicy)
		.build();
		
		// Handler para assets locales
		String basePath = assetSubfolder.isEmpty() ? "" : assetSubfolder;
		fileLoader.addPathHandler("/", PathHandler.fromAssets(
		context.getAssets(), basePath
		));
		
		// Handler para archivos en cache del sistema
		fileLoader.addPathHandler("/cache/", PathHandler.fromFile(
		context.getCacheDir()
		));
		
		// Configurar WebView
		applyWebViewSettings(webView);
		webView.setWebViewClient(new VirtualHostClient());
		webView.setWebChromeClient(new ConsoleLogger());
		
		logConfig();
		return this;
	}
	
	// ==================== LIFECYCLE ====================
	
	/**
	* Debe llamarse desde Activity.onTrimMemory()
	* Libera memoria del caché según el nivel
	*
	* @param level Nivel de trim (de ComponentCallbacks2)
	*/
	public void onTrimMemory(int level) {
		if (fileLoader != null) {
			fileLoader.trimMemory(level);
			if (debugMode) {
				Log.d(TAG, "🧹 Memory trimmed: level " + level);
			}
		}
	}
	
	/**
	* Debe llamarse desde Activity.onDestroy()
	* Limpia todos los recursos y desregistra los clientes del WebView
	*/
	public void destroy() {
		if (webView != null) {
			// Desregistrar clientes antes de nullificar
			webView.setWebViewClient(new android.webkit.WebViewClient());
			webView.setWebChromeClient(new android.webkit.WebChromeClient());
		}
		if (fileLoader != null) {
			fileLoader.clearCache();
		}
		webView = null;
		pageListener = null;
		jsBridge = null;
		if (debugMode) {
			Log.d(TAG, "💀 " + WebVirtVersion.FULL + " destroyed");
		}
	}
	
	/**
	* Libera el caché de assets sin destruir la instancia
	*/
	public void clearCache() {
		if (fileLoader != null) {
			fileLoader.clearCache();
		}
	}
	
	/**
	* Invalida un asset específico en el caché
	* @param path Ruta del asset a invalidar
	*/
	public void invalidateCache(@NonNull String path) {
		if (fileLoader != null) {
			fileLoader.invalidateCache(path);
		}
	}
	
	// ==================== GETTERS ====================
	
	/** @return WebView vinculado o null si aún no se llamó bind() */
	@Nullable
	public WebView getWebView() {
		return webView;
	}
	
	/** @return URL base del host virtual ej: "https://app.local/" */
	@NonNull
	public String getBaseUrl() {
		return "https://" + host + "/";
	}
	
	/** @return Instancia de WebVirtFileLoader para configuraciones avanzadas */
	@Nullable
	public WebVirtFileLoader getFileLoader() {
		return fileLoader;
	}
	
	/** @return Host virtual configurado */
	@NonNull
	public String getHost() {
		return host;
	}
	
	/** @return true si el modo debug está activado */
	public boolean isDebugMode() {
		return debugMode;
	}
	
	/** @return true si el modo offline está activado */
	public boolean isOfflineOnly() {
		return offlineOnly;
	}
	
	// ==================== INTERNO ====================
	
	/**
	* Aplica configuración recomendada de WebSettings para SPAs
	*/
	private void applyWebViewSettings(WebView webView) {
		android.webkit.WebSettings s = webView.getSettings();
		
		// Rendimiento
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setDatabaseEnabled(true);
		
		// Acceso a archivos
		s.setAllowFileAccess(true);
		s.setAllowContentAccess(true);
		
		// Viewport y zoom
		s.setLoadWithOverviewMode(true);
		s.setUseWideViewPort(true);
		s.setTextZoom(100);
		s.setSupportZoom(false);
		s.setBuiltInZoomControls(false);
		s.setDisplayZoomControls(false);
		
		// Seguridad
		s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
		
		// Safe Browsing (Android 8+)
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
			s.setSafeBrowsingEnabled(true);
		}
		
		// Cookies
		android.webkit.CookieManager.getInstance().setAcceptCookie(true);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
		}
	}
	
	/**
	* Normaliza una ruta para comparación consistente
	*/
	private String normalizePath(String path) {
		if (path == null) return "/";
		
		String p = path.trim();
		if (!p.startsWith("/")) p = "/" + p;
		if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
		
		// Remover query string
		int q = p.indexOf('?');
		if (q > 0) p = p.substring(0, q);
		
		// Remover fragment
		int f = p.indexOf('#');
		if (f > 0) p = p.substring(0, f);
		
		return p;
	}
	
	/**
	* Log condicional según debugMode
	*/
	private void log(String msg) {
		if (debugMode) Log.d(TAG, msg);
	}
	
	/**
	* Imprime configuración inicial
	*/
	private void logConfig() {
		Log.d(TAG, "╔══════════════════════════════════════════╗");
		Log.d(TAG, "║     " + WebVirtVersion.FULL + " Initialized           ║");
		Log.d(TAG, "╠══════════════════════════════════════════╣");
		Log.d(TAG, "║ Host:    " + String.format("%-32s", host) + "║");
		Log.d(TAG, "║ Assets:  " + String.format("%-32s",
		assetSubfolder.isEmpty() ? "assets/" : "assets/" + assetSubfolder + "/") + "║");
		Log.d(TAG, "║ Cache:   " + String.format("%-32s",
		cacheEntries + " entries, " + (maxCacheSize / 1024 / 1024) + "MB max") + "║");
		Log.d(TAG, "║ Debug:   " + String.format("%-32s", debugMode ? "ON" : "OFF") + "║");
		Log.d(TAG, "║ Offline: " + String.format("%-32s", offlineOnly ? "ON" : "OFF") + "║");
		Log.d(TAG, "║ Routes:  " + String.format("%-32s", routes.size()) + "║");
		Log.d(TAG, "║ Bridge:  " + String.format("%-32s", jsBridge != null ? "YES" : "NO") + "║");
		Log.d(TAG, "║ CSP:     " + String.format("%-32s", "custom") + "║");
		Log.d(TAG, "╚══════════════════════════════════════════╝");
	}
	
	// ==================== WEBVIEW CLIENT ====================
	
	/**
	* Cliente WebView interno que maneja:
	* - Seguridad (offline, dominios)
	* - Rutas virtuales
	* - Assets con WebVirtFileLoader
	* - SPA fallback (con métrica opcional)
	* - JSBridge injection (en cada navegación)
	*/
	private class VirtualHostClient extends android.webkit.WebViewClient {
		
		private String lastReadyUrl = null;
		
		@Override
		public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
			super.onPageStarted(view, url, favicon);
			if (!url.equals(lastReadyUrl)) {
				lastReadyUrl = null;
			}
		}
		
		@Nullable
		@Override
		public android.webkit.WebResourceResponse shouldInterceptRequest(
		@NonNull WebView view,
		@NonNull android.webkit.WebResourceRequest request) {
			
			String url = request.getUrl().toString();
			String reqHost = request.getUrl().getHost();
			String path = request.getUrl().getPath();
			if (path == null || path.isEmpty()) path = "/";
			
			boolean isVirtual = host.equals(reqHost);
			
			// 1. SEGURIDAD: Modo offline bloquea todo lo externo
			if (offlineOnly && !isVirtual) {
				log("🔒 Blocked (offline): " + url);
				return emptyResponse();
			}
			
			// 2. SEGURIDAD: Validar dominios externos
			if (!isVirtual && !allowedDomains.isEmpty()) {
				if (!isDomainAllowed(reqHost)) {
					log("⛔ Blocked domain: " + reqHost);
					return emptyResponse();
				}
			}
			
			// 3. Si no es nuestro host y no está bloqueado, dejar pasar al sistema
			if (!isVirtual) {
				return super.shouldInterceptRequest(view, request);
			}
			
			// 4. RUTAS VIRTUALES (mayor prioridad que assets)
			RouteHandler routeHandler = findRoute(path);
			if (routeHandler != null) {
				try {
					WebVirtRequest vr = WebVirtRequest.from(request, host);
					log("🛣️  Route: " + path);
					return routeHandler.handle(vr);
					} catch (Exception e) {
					Log.e(TAG, "💥 Route error: " + path, e);
					return WebVirtResponses.serverError(e.getMessage());
				}
			}
			
			// 5. WEBVIRTFILELOADER para assets estáticos
			android.webkit.WebResourceResponse response = fileLoader.shouldInterceptRequest(request);
			if (response != null) {
				return response;
			}
			
			// 6. SPA FALLBACK: Rutas sin extensión → index.html
			if (!path.contains(".") && !path.endsWith("/index.html")) {
				log("🔄 SPA Fallback: " + path + " → index.html");
				// [DEV] Descomentar para métricas:
				// WebVirtMetrics.recordSpaFallback();
				return fileLoader.shouldInterceptRequest(createIndexRequest(request));
			}
			
			// 7. 404: No encontrado
			log("❌ 404: " + path);
			return WebVirtResponses.notFound("File not found: " + path);
		}
		
		@Override
		public void onPageFinished(@NonNull WebView view, @NonNull String url) {
			super.onPageFinished(view, url);
			
			if (lastReadyUrl == null && url.startsWith("https://" + host)) {
				lastReadyUrl = url;
				
				if (jsBridge != null) {
					try {
						jsBridge.inject(view);
						log("🌉 JSBridge injected");
						} catch (Exception e) {
						Log.e(TAG, "💥 JSBridge injection failed", e);
					}
				}
				
				if (pageListener != null) {
					try {
						pageListener.onPageReady(view);
						log("✅ Page ready callback fired");
						} catch (Exception e) {
						Log.e(TAG, "💥 Page ready callback failed", e);
					}
				}
			}
		}
		
		@Override
		public void onReceivedError(@NonNull WebView view,
		android.webkit.WebResourceRequest request,
		android.webkit.WebResourceError error) {
			super.onReceivedError(view, request, error);
			if (debugMode) {
				Log.e(TAG, "❌ WebView error: " + error.getDescription() +
				" for " + request.getUrl());
			}
		}
		
		// ==================== HELPERS ====================
		
		private RouteHandler findRoute(String path) {
			String normalized = normalizePath(path);
			
			RouteHandler exact = routes.get(normalized);
			if (exact != null) return exact;
			
			int q = normalized.indexOf('?');
			if (q > 0) {
				return routes.get(normalized.substring(0, q));
			}
			
			return null;
		}
		
		private boolean isDomainAllowed(String host) {
			if (host == null || host.isEmpty()) return false;
			
			String lowerHost = host.toLowerCase();
			for (String allowed : allowedDomains) {
				if (lowerHost.equals(allowed) || lowerHost.endsWith("." + allowed)) {
					return true;
				}
			}
			return false;
		}
		
		private android.webkit.WebResourceRequest createIndexRequest(
		android.webkit.WebResourceRequest original) {
			android.net.Uri indexUri = android.net.Uri.parse("https://" + host + "/index.html");
			
			return new android.webkit.WebResourceRequest() {
				@Override
				public android.net.Uri getUrl() {
					return indexUri;
				}
				
				@Override
				public boolean isForMainFrame() {
					return original.isForMainFrame();
				}
				
				@Override
				public boolean isRedirect() {
					return false;
				}
				
				@Override
				public boolean hasGesture() {
					return original.hasGesture();
				}
				
				@Override
				public String getMethod() {
					return "GET";
				}
				
				@Override
				public Map<String, String> getRequestHeaders() {
					return new HashMap<>();
				}
			};
		}
		
		private android.webkit.WebResourceResponse emptyResponse() {
			return new android.webkit.WebResourceResponse(
			"text/plain",
			"UTF-8",
			new ByteArrayInputStream(new byte[0])
			);
		}
	}
	
	// ==================== CHROME CLIENT ====================
	
	private class ConsoleLogger extends android.webkit.WebChromeClient {
		
		@Override
		public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
			if (debugMode) {
				String level = consoleMessage.messageLevel().name();
				Log.d(TAG, "[JS:" + level + ":" + consoleMessage.lineNumber() + "] " +
				consoleMessage.message());
			}
			return true;
		}
		
		@Override
		public void onProgressChanged(WebView view, int newProgress) {
			super.onProgressChanged(view, newProgress);
			if (debugMode && newProgress < 100) {
				Log.v(TAG, "📊 Loading: " + newProgress + "%");
			}
		}
	}
	
	// ==================== INTERFACES PÚBLICAS ====================
	
	@FunctionalInterface
	public interface JSBridge {
		void inject(@NonNull WebView webView);
	}
	
	@FunctionalInterface
	public interface WebVirtPageListener {
		void onPageReady(@NonNull WebView view);
	}
}