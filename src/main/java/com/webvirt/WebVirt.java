package com.webvirt;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webvirt.benchmark.WebVirtBenchmark;
import com.webvirt.extensions.cache.CachePolicy;
import com.webvirt.extensions.compression.CompressionStrategy;
import com.webvirt.extensions.manifest.AssetManifest;
import com.webvirt.loader.WebVirtFileLoader;
import com.webvirt.loader.PathHandler;

import java.io.ByteArrayInputStream;
import java.util.*;

/**
* WebVirt v3.5.2 — Hybrid Web Runtime Engine for Android
*
* Optimizado para Android 11+
* API mínima, cold start rápido y caché inteligente.
*/
public class WebVirt {
	
	private static final String TAG = "WebVirt";
	private static final String VERSION = "WebVirt v3.5.2";
	
	private final Context context;
	
	private String host = "app.local";
	private String assetSubfolder = "";
	private boolean offlineOnly = false;
	private long maxFileSize = 10 * 1024 * 1024;
	private long maxCacheSize = 50 * 1024 * 1024;
	private int cacheEntries = 200;
	
	private WebView webView;
	private WebVirtFileLoader fileLoader;
	
	private final Map<String, RouteHandler> routes = new LinkedHashMap<>();
	private final Set<String> allowedDomains = new HashSet<>();
	
	private WebVirtPageListener pageListener;
	private JSBridge jsBridge;
	private WebVirtMetricsCollector metricsCollector = WebVirtMetricsCollector.NOOP;
	
	private CompressionStrategy compressionStrategy = CompressionStrategy.NOOP;
	private CachePolicy cachePolicy = CachePolicy.SPA_IMMUTABLE;
	private AssetManifest assetManifest = AssetManifest.NOOP;
	
	private WebVirtBenchmark benchmark;
	
	private WebVirt(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}
	
	@NonNull
	public static WebVirt with(@NonNull Context context) {
		return new WebVirt(context);
	}
	
	public WebVirt host(@NonNull String host) {
		if (host != null && !host.trim().isEmpty()) {
			this.host = host.trim().toLowerCase();
		}
		return this;
	}
	
	public WebVirt subfolder(@NonNull String subfolder) {
		if (subfolder != null) {
			this.assetSubfolder = subfolder.trim().replaceAll("^/+|/+$", "");
		}
		return this;
	}
	
	public WebVirt offlineOnly(boolean offline) {
		this.offlineOnly = offline;
		return this;
	}
	
	public WebVirt maxFileSize(long bytes) {
		if (bytes > 0) this.maxFileSize = bytes;
		return this;
	}
	
	public WebVirt maxCacheSize(long bytes) {
		if (bytes > 0) this.maxCacheSize = bytes;
		return this;
	}
	
	public WebVirt cacheEntries(int entries) {
		if (entries > 0) this.cacheEntries = entries;
		return this;
	}
	
	public WebVirt onPageReady(@NonNull WebVirtPageListener listener) {
		this.pageListener = listener;
		return this;
	}
	
	public WebVirt withBridge(@NonNull JSBridge bridge) {
		this.jsBridge = bridge;
		return this;
	}
	
	public WebVirt route(@NonNull String path, @NonNull RouteHandler handler) {
		routes.put(normalizePath(path), handler);
		return this;
	}
	
	public WebVirt allowDomain(@NonNull String domain) {
		allowedDomains.add(domain.toLowerCase().trim());
		return this;
	}
	
	public WebVirt allowDomains(@NonNull String... domains) {
		for (String domain : domains) allowDomain(domain);
		return this;
	}
	
	public WebVirt withCompressionStrategy(@NonNull CompressionStrategy strategy) {
		if (strategy != null) this.compressionStrategy = strategy;
		return this;
	}
	
	public WebVirt withCachePolicy(@NonNull CachePolicy policy) {
		if (policy != null) this.cachePolicy = policy;
		return this;
	}
	
	public WebVirt withAssetManifest(@NonNull AssetManifest manifest) {
		if (manifest != null) this.assetManifest = manifest;
		return this;
	}
	
	/**
	* Activa métricas por defecto con WebVirtMetrics.
	* v3.5.2: Solo crea archivo de log cuando se usa este método.
	*/
	public WebVirt withMetrics() {
		WebVirtMetrics metrics = new WebVirtMetrics();
		metrics.startSession();  // ← Iniciar sesión explícitamente
		this.metricsCollector = metrics;
		return this;
	}
	
	/**
	* Inyecta un collector de métricas personalizado.
	* v3.5.2: Si es WebVirtMetrics, activa sesión y logging.
	*/
	public WebVirt withMetricsCollector(@NonNull WebVirtMetricsCollector collector) {
		if (collector != null) {
			this.metricsCollector = collector;
			collector.startSession();
		}
		return this;
	}
	
	public void runBenchmark() {
		if (webView == null) {
			Log.w(TAG, "Benchmark: WebView principal no vinculado aún.");
			return;
		}
		if (benchmark != null) {
			benchmark.stop();
		}
		benchmark = new WebVirtBenchmark(context, this, getBaseUrl());
		benchmark.runQuickBenchmark();
	}
	
	@NonNull
	public WebVirt bind(@NonNull WebView webView) {
		this.webView = webView;
		
		if (assetSubfolder.isEmpty()) {
			assetSubfolder = detectSubfolder();
		}
		
		fileLoader = new WebVirtFileLoader.Builder(context)
		.setDomain(host)
		.setDebugMode(false)
		.setCacheEntries(cacheEntries)
		.setMaxFileSize(maxFileSize)
		.setMaxCacheSize(maxCacheSize)
		.setMetricsCollector(metricsCollector)
		.setCompressionStrategy(compressionStrategy)
		.setCachePolicy(cachePolicy)
		.setAssetManifest(assetManifest)
		.build();
		
		fileLoader.addPathHandler("/", PathHandler.fromAssets(context.getAssets(), assetSubfolder));
		fileLoader.addPathHandler("/cache/", PathHandler.fromFile(context.getCacheDir()));
		
		applyWebViewSettings(webView);
		webView.setWebViewClient(new VirtualHostClient());
		webView.setWebChromeClient(new ConsoleLogger());
		
		Log.d(TAG, VERSION + " | host=" + host + " | assets=" +
		(assetSubfolder.isEmpty() ? "root" : assetSubfolder));
		
		return this;
	}
	
	public void onTrimMemory(int level) {
		if (fileLoader != null) fileLoader.trimMemory(level);
	}
	
	public void destroy() {
		if (benchmark != null) {
			benchmark.stop();
			benchmark = null;
		}
		
		if (metricsCollector instanceof WebVirtMetrics) {
			WebVirtMetrics metrics = (WebVirtMetrics) metricsCollector;
			if (metrics.getTotalAssetsLoaded() > 0) {
				metrics.endSession();
				metrics.generateReport(context);
			}
		}
		
		if (webView != null) {
			webView.setWebViewClient(new android.webkit.WebViewClient());
			webView.setWebChromeClient(new android.webkit.WebChromeClient());
		}
		
		if (fileLoader != null) {
			fileLoader.destroy();
		}
		
		webView = null;
		pageListener = null;
		jsBridge = null;
	}
	
	public void clearCache() {
		if (fileLoader != null) fileLoader.clearCache();
	}
	
	public void invalidateCache(@NonNull String path) {
		if (fileLoader != null) fileLoader.invalidateCache(path);
	}
	
	@Nullable
	public WebView getWebView() {
		return webView;
	}
	
	@NonNull
	public String getBaseUrl() {
		return "https://" + host + "/";
	}
	
	@Nullable
	public WebVirtFileLoader getFileLoader() {
		return fileLoader;
	}
	
	@NonNull
	public String getHost() {
		return host;
	}
	
	@NonNull
	public WebVirtMetricsCollector getMetricsCollector() {
		return metricsCollector;
	}
	
	private String detectSubfolder() {
		try {
			String[] wwwList = context.getAssets().list("www");
			if (wwwList != null) {
				for (String f : wwwList) {
					if ("index.html".equals(f) || "index.htm".equals(f)) return "www";
				}
			}
		} catch (Exception ignored) {}
		try {
			String[] assetsList = context.getAssets().list("assets");
			if (assetsList != null) {
				for (String f : assetsList) {
					if ("index.html".equals(f) || "index.htm".equals(f)) return "assets";
				}
			}
		} catch (Exception ignored) {}
		try {
			context.getAssets().open("index.html").close();
			return "";
		} catch (Exception ignored) {}
		return "";
	}
	
	private void applyWebViewSettings(WebView webView) {
		android.webkit.WebSettings s = webView.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setDatabaseEnabled(true);
		s.setAllowFileAccess(false);
		s.setAllowContentAccess(false);
		s.setLoadWithOverviewMode(true);
		s.setUseWideViewPort(true);
		s.setTextZoom(100);
		s.setSupportZoom(false);
		s.setBuiltInZoomControls(false);
		s.setDisplayZoomControls(false);
		s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
			s.setSafeBrowsingEnabled(true);
		}
		android.webkit.CookieManager.getInstance().setAcceptCookie(true);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
		}
	}
	
	private String normalizePath(String path) {
		if (path == null) return "/";
		String p = path.trim();
		if (!p.startsWith("/")) p = "/" + p;
		if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
		int q = p.indexOf('?');
		if (q > 0) p = p.substring(0, q);
		int f = p.indexOf('#');
		if (f > 0) p = p.substring(0, f);
		return p;
	}
	
	private class VirtualHostClient extends android.webkit.WebViewClient {
		private String lastReadyUrl = null;
		
		@Override
		public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
			super.onPageStarted(view, url, favicon);
			if (!url.equals(lastReadyUrl)) lastReadyUrl = null;
		}
		
		@Nullable
		@Override
		public android.webkit.WebResourceResponse shouldInterceptRequest(
		@NonNull WebView view,
		@NonNull android.webkit.WebResourceRequest request) {
			
			String reqHost = request.getUrl().getHost();
			String path = request.getUrl().getPath();
			if (path == null || path.isEmpty()) path = "/";
			boolean isVirtual = host.equals(reqHost);
			
			if (offlineOnly && !isVirtual) return emptyResponse();
			if (!isVirtual && !allowedDomains.isEmpty()) {
				if (!isDomainAllowed(reqHost)) return emptyResponse();
			}
			if (!isVirtual) return super.shouldInterceptRequest(view, request);
			
			RouteHandler routeHandler = findRoute(path);
			if (routeHandler != null) {
				try {
					return routeHandler.handle(WebVirtRequest.from(request, host));
					} catch (Exception e) {
					Log.e(TAG, "Route error: " + path, e);
					return WebVirtResponses.serverError(e.getMessage());
				}
			}
			
			android.webkit.WebResourceResponse response = fileLoader.shouldInterceptRequest(request);
			if (response != null) return response;
			
			if (!path.contains(".") && !path.endsWith("/index.html")) {
				metricsCollector.recordSpaFallback();
				return fileLoader.shouldInterceptRequest(createIndexRequest(request));
			}
			
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
						} catch (Exception e) {
						Log.e(TAG, "JSBridge injection failed", e);
					}
				}
				if (pageListener != null) {
					try {
						pageListener.onPageReady(view);
						} catch (Exception e) {
						Log.e(TAG, "Page ready callback failed", e);
					}
				}
			}
		}
		
		private RouteHandler findRoute(String path) {
			String normalized = normalizePath(path);
			return routes.get(normalized);
		}
		
		private boolean isDomainAllowed(String host) {
			if (host == null || host.isEmpty()) return false;
			String lowerHost = host.toLowerCase();
			for (String allowed : allowedDomains) {
				if (lowerHost.equals(allowed) || lowerHost.endsWith("." + allowed)) return true;
			}
			return false;
		}
		
		private android.webkit.WebResourceRequest createIndexRequest(
		android.webkit.WebResourceRequest original) {
			android.net.Uri indexUri = android.net.Uri.parse("https://" + host + "/index.html");
			return new android.webkit.WebResourceRequest() {
				@Override public android.net.Uri getUrl() { return indexUri; }
				@Override public boolean isForMainFrame() { return original.isForMainFrame(); }
				@Override public boolean isRedirect() { return false; }
				@Override public boolean hasGesture() { return original.hasGesture(); }
				@Override public String getMethod() { return "GET"; }
				@Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
			};
		}
		
		private android.webkit.WebResourceResponse emptyResponse() {
			return new android.webkit.WebResourceResponse(
			"text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
		}
	}
	
	private class ConsoleLogger extends android.webkit.WebChromeClient {
		@Override
		public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
			Log.d(TAG, "[JS Console] " + consoleMessage.message() +
			" (line: " + consoleMessage.lineNumber() + ")");
			return true;
		}
	}
	
	@FunctionalInterface
	public interface JSBridge {
		void inject(@NonNull WebView webView);
	}
	
	@FunctionalInterface
	public interface WebVirtPageListener {
		void onPageReady(@NonNull WebView view);
	}
}