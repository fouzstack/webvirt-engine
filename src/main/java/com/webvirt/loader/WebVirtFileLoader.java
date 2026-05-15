package com.webvirt.loader;

import static com.webvirt.WebVirtResponses.escapeHtml;
import static com.webvirt.WebVirtVersion.FULL;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.webvirt.WebVirtMetricsCollector;
import com.webvirt.extensions.cache.CachePolicy;
import com.webvirt.extensions.compression.CompressionStrategy;
import com.webvirt.extensions.manifest.AssetManifest;
import com.webvirt.extensions.manifest.AssetManifestEntry;

import java.io.*;
import java.net.URLDecoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
* WebVirtFileLoader v3.5.2 — Production Ready con Precarga Controlada
*
* Núcleo del runtime HTTP embebido para WebView en Android.
*
* MEJORAS v3.5.2:
* - Integración con CacheManager v3.5.2 (headers precalculados + CRC32 ETag)
* - Precarga con backpressure (MAX_PRECACHED_ASSETS=20, MAX_PRECACHED_BYTES=5MB)
* - Request Coalescing con limpieza automática en finally
* - Lectura lock-free de prefijos (snapshot inmutable)
* - Sanitización de paths compatible con APIs antiguas
* - Protección OOM con readFullyLimited()
* - CompressionStrategy.NOOP por defecto (correcto para WebView)
* - SIN dependencia de LoggingUtil (no crea archivos, solo Log.d)
* - Notifica eventos al WebVirtMetricsCollector (él decide si persiste)
*
* COMPATIBILIDAD HACIA ATRÁS:
* - Toda la API pública se mantiene sin cambios
* - CacheManager.put() antiguo sigue soportado
* - Métodos de precarga públicos intactos
*
* @since 3.5.2
*/
public class WebVirtFileLoader {
	
	private static final String TAG = "WebVirtFileLoader";
	
	private final Context context;
	private final Map<String, PathHandler> handlers;
	
	// v3.5.2: Snapshot inmutable para lectura lock-free de prefijos
	private volatile List<String> sortedPrefixes;
	private final Object prefixWriteLock = new Object();
	
	private final SecurityManager securityManager;
	private final CacheManager cacheManager;
	private final MimeTypeResolver mimeResolver;
	private final String allowedDomain;
	private final long maxCacheSizeBytes;
	private final String cspPolicy;
	private final boolean mergeHeaders;
	
	private final WebVirtMetricsCollector metricsCollector;
	
	private final CompressionStrategy compressionStrategy;
	private final CachePolicy cachePolicy;
	private final AssetManifest assetManifest;
	
	// Request Coalescing con InFlightRequest
	private final ConcurrentHashMap<String, InFlightRequest> inFlightRequests = new ConcurrentHashMap<>();
	
	// Precarga asíncrona con backpressure
	private final ExecutorService precacheExecutor;
	private final Set<String> precachedAssets = ConcurrentHashMap.newKeySet();
	private volatile boolean precacheEnabled;
	private final AtomicBoolean precacheStarted = new AtomicBoolean(false);
	
	// v3.5.2: Límites de precarga
	private static final int MAX_PRECACHED_ASSETS = 20;
	private static final long MAX_PRECACHED_BYTES = 5 * 1024 * 1024; // 5 MB
	private final AtomicLong precachedBytes = new AtomicLong(0);
	
	// Límite de lectura para assets cacheables
	private static final int MAX_CACHEABLE_READ_BYTES = 5 * 1024 * 1024; // 5 MB
	
	private static final ThreadLocal<SimpleDateFormat> HTTP_DATE_FORMAT =
	new ThreadLocal<SimpleDateFormat>() {
		@Override
		protected SimpleDateFormat initialValue() {
			SimpleDateFormat sdf = new SimpleDateFormat(
			"EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
			return sdf;
		}
	};
	
	private WebVirtFileLoader(Builder builder) {
		this.context = builder.context;
		this.handlers = new ConcurrentHashMap<>(4);
		this.sortedPrefixes = Collections.emptyList();
		this.securityManager = new SecurityManager(builder.allowedExtensions, builder.maxFileSize);
		this.cacheManager = new CacheManager(builder.maxCacheSizeBytes, builder.cacheEntries);
		this.mimeResolver = new MimeTypeResolver();
		this.allowedDomain = builder.allowedDomain;
		this.maxCacheSizeBytes = builder.maxCacheSizeBytes;
		this.cspPolicy = builder.cspPolicy;
		this.mergeHeaders = builder.mergeHeaders;
		this.metricsCollector = builder.metricsCollector;
		this.compressionStrategy = builder.compressionStrategy;
		this.cachePolicy = builder.cachePolicy;
		this.assetManifest = builder.assetManifest;
		this.precacheEnabled = builder.enablePrecache;
		
		this.precacheExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "WebVirt-Precache");
				t.setPriority(Thread.MIN_PRIORITY);
				t.setDaemon(true);
				return t;
			}
		});
	}
	
	// ==================== BUILDER ====================
	
	public static class Builder {
		private final Context context;
		private String allowedDomain = "app.local";
		private Set<String> allowedExtensions;
		private int cacheEntries = 200;
		private long maxFileSize = 10 * 1024 * 1024;
		private long maxCacheSizeBytes = 50 * 1024 * 1024;
		private boolean mergeHeaders = true;
		private boolean debugMode = false;
		private boolean enablePrecache = true;
		
		private String cspPolicy =
		"default-src 'self'; " +
		"script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
		"style-src 'self' 'unsafe-inline'; " +
		"img-src 'self' data: blob:; " +
		"font-src 'self' data:; " +
		"media-src 'self' data: blob:; " +
		"connect-src 'self' data: blob:;";
		
		private WebVirtMetricsCollector metricsCollector = WebVirtMetricsCollector.NOOP;
		private CompressionStrategy compressionStrategy = CompressionStrategy.NOOP;
		private CachePolicy cachePolicy = CachePolicy.SPA_IMMUTABLE;
		private AssetManifest assetManifest = AssetManifest.NOOP;
		
		public Builder(Context context) {
			this.context = context.getApplicationContext();
			this.allowedExtensions = SecurityManager.getDefaultAllowedExtensions();
		}
		
		public Builder setDomain(String domain) { this.allowedDomain = domain; return this; }
		public Builder setDebugMode(boolean debug) { this.debugMode = debug; return this; }
		public Builder setCacheEntries(int entries) { if (entries > 0) this.cacheEntries = entries; return this; }
		public Builder setMaxFileSize(long bytes) { if (bytes > 0) this.maxFileSize = bytes; return this; }
		public Builder setMaxCacheSize(long bytes) { if (bytes > 0) this.maxCacheSizeBytes = bytes; return this; }
		public Builder setCspPolicy(String csp) { if (csp != null && !csp.trim().isEmpty()) this.cspPolicy = csp.trim(); return this; }
		public Builder setMergeHeaders(boolean merge) { this.mergeHeaders = merge; return this; }
		public Builder setAllowedExtensions(Set<String> extensions) { this.allowedExtensions = new HashSet<>(extensions); return this; }
		public Builder setMetricsCollector(WebVirtMetricsCollector collector) { if (collector != null) this.metricsCollector = collector; return this; }
		public Builder setCompressionStrategy(CompressionStrategy strategy) { if (strategy != null) this.compressionStrategy = strategy; return this; }
		public Builder setCachePolicy(CachePolicy policy) { if (policy != null) this.cachePolicy = policy; return this; }
		public Builder setAssetManifest(AssetManifest manifest) { if (manifest != null) this.assetManifest = manifest; return this; }
		public Builder setPrecacheEnabled(boolean enabled) { this.enablePrecache = enabled; return this; }
		
		public WebVirtFileLoader build() {
			return new WebVirtFileLoader(this);
		}
	}
	
	// ==================== API PÚBLICA ====================
	
	/**
	* Registra un handler para un prefijo de path.
	* v3.5.2: Usa snapshot inmutable para lectura lock-free.
	*/
	public WebVirtFileLoader addPathHandler(String pathPrefix, PathHandler handler) {
		if (pathPrefix != null && handler != null) {
			handlers.put(pathPrefix, handler);
			synchronized (prefixWriteLock) {
				List<String> newList = new ArrayList<>(sortedPrefixes);
				if (!newList.contains(pathPrefix)) {
					newList.add(pathPrefix);
					newList.sort((a, b) -> Integer.compare(b.length(), a.length()));
					sortedPrefixes = Collections.unmodifiableList(newList);
				}
			}
		}
		return this;
	}
	
	/**
	* Punto de entrada principal del runtime HTTP.
	*/
	public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
		long startTime = SystemClock.elapsedRealtime();
		boolean fromCache = false;
		long fileSize = 0;
		
		Uri uri = request.getUrl();
		
		String scheme = uri.getScheme();
		if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
		
		String host = uri.getHost();
		if (!allowedDomain.equals(host)) return null;
		
		String path = extractSafePath(uri);
		if (path == null) return null;
		
		if (precacheEnabled && isMainHtml(path)) {
			triggerPrecache();
		}
		
		String resolvedPath = path;
		String integrityHash = null;
		AssetManifestEntry manifestEntry = assetManifest.resolve(path);
		if (manifestEntry != null) {
			resolvedPath = manifestEntry.hashedPath;
			integrityHash = manifestEntry.integrity;
		}
		
		if (!securityManager.isPathAllowed(resolvedPath)) return null;
		
		String rangeHeader = getRequestHeader(request, "Range");
		String ifNoneMatch = getRequestHeader(request, "If-None-Match");
		String ifModifiedSince = getRequestHeader(request, "If-Modified-Since");
		
		if (rangeHeader != null) {
			metricsCollector.recordRangeRequest();
			return handleRangeRequest(resolvedPath, rangeHeader, request);
		}
		
		boolean cacheable = isCacheableStatic(resolvedPath);
		
		if (cacheable) {
			CacheManager.CacheEntry cached = cacheManager.getEntry(resolvedPath);
			if (cached != null) {
				if (ifModifiedSince != null) {
					try {
						Date ifModifiedDate = HTTP_DATE_FORMAT.get().parse(ifModifiedSince);
						if (cached.lastModified <= ifModifiedDate.getTime()) {
							metricsCollector.recordAssetLoad(resolvedPath,
							SystemClock.elapsedRealtime() - startTime, true, 0);
							return cached.to304Response();
						}
					} catch (ParseException ignored) {}
				}
				if (ifNoneMatch != null && ifNoneMatch.equals(cached.etag)) {
					metricsCollector.recordAssetLoad(resolvedPath,
					SystemClock.elapsedRealtime() - startTime, true, 0);
					return cached.to304Response();
				}
				
				fromCache = true;
				WebResourceResponse response = cached.toResponse();
				if (response != null) {
					fileSize = cached.size;
					metricsCollector.recordAssetLoad(resolvedPath,
					SystemClock.elapsedRealtime() - startTime, true, fileSize);
				}
				return response;
			}
			
			InFlightRequest inFlight = inFlightRequests.get(resolvedPath);
			
			if (inFlight != null && inFlight.completed) {
				cached = cacheManager.getEntry(resolvedPath);
				if (cached != null) {
					fromCache = true;
					WebResourceResponse response = cached.toResponse();
					if (response != null) {
						fileSize = cached.size;
						metricsCollector.recordAssetLoad(resolvedPath,
						SystemClock.elapsedRealtime() - startTime, true, fileSize);
					}
					return response;
				}
				inFlightRequests.remove(resolvedPath);
				inFlight = null;
			}
			
			if (inFlight == null) {
				inFlight = new InFlightRequest();
				InFlightRequest existing = inFlightRequests.putIfAbsent(resolvedPath, inFlight);
				if (existing != null) {
					inFlight = existing;
				}
			}
			
			synchronized (inFlight.lock) {
				try {
					if (inFlight.completed && inFlight.cached) {
						cached = cacheManager.getEntry(resolvedPath);
						if (cached != null) {
							fromCache = true;
							WebResourceResponse response = cached.toResponse();
							if (response != null) {
								fileSize = cached.size;
								metricsCollector.recordAssetLoad(resolvedPath,
								SystemClock.elapsedRealtime() - startTime, true, fileSize);
							}
							return response;
						}
					}
					
					WebResourceResponse response = loadAsset(resolvedPath, request, startTime,
					cacheable, fromCache, integrityHash);
					
					inFlight.completed = true;
					inFlight.cached = (response != null);
					
					return response;
					} finally {
					if (inFlight.completed) {
						inFlightRequests.remove(resolvedPath, inFlight);
					}
				}
			}
		}
		
		return loadAsset(resolvedPath, request, startTime, false, false, integrityHash);
	}
	
	/**
	* Carga un asset desde el PathHandler.
	*/
	private WebResourceResponse loadAsset(String resolvedPath, WebResourceRequest request,
	long startTime, boolean cacheable, boolean fromCache,
	String integrityHash) {
		long fileSize = 0;
		
		PathHandler handler = findHandler(resolvedPath);
		if (handler == null) return null;
		
		try {
			WebResourceResponse response = handler.handle(resolvedPath, request);
			if (response == null) return null;
			
			boolean isBAIS = response.getData() instanceof ByteArrayInputStream;
			
			if (isBAIS) {
				try { fileSize = response.getData().available(); } catch (IOException ignored) {}
			}
			
			if (cacheable && !fromCache && isBAIS) {
				cacheResponseWithHeaders(resolvedPath, response, integrityHash);
			}
			
			response = enrichResponse(response, resolvedPath, integrityHash);
			
			metricsCollector.recordAssetLoad(resolvedPath,
			SystemClock.elapsedRealtime() - startTime, false, fileSize);
			
			return response;
			
			} catch (Exception e) {
			metricsCollector.recordHttpError();
			Log.e(TAG, "Error loading: " + resolvedPath, e);
			return createErrorResponse(500, "Internal Server Error");
		}
	}
	
	// ==================== PRECARGA ASÍNCRONA ====================
	
	private boolean isMainHtml(String path) {
		return path.equals("/") ||
		path.equals("/index.html") ||
		(path.endsWith(".html") && !path.contains("/assets/"));
	}
	
	private void triggerPrecache() {
		if (!precacheEnabled || precacheExecutor.isShutdown()) return;
		
		if (!precacheStarted.compareAndSet(false, true)) {
			return;
		}
		
		precacheExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					int precachedCount = 0;
					
					for (Map.Entry<String, PathHandler> entry : handlers.entrySet()) {
						if (precachedCount >= MAX_PRECACHED_ASSETS) break;
						if (precachedBytes.get() >= MAX_PRECACHED_BYTES) break;
						
						String path = entry.getKey();
						if (isCriticalAsset(path)) {
							String resolvedPath = path;
							AssetManifestEntry manifestEntry = assetManifest.resolve(path);
							if (manifestEntry != null) {
								resolvedPath = manifestEntry.hashedPath;
							}
							if (precacheAsset(resolvedPath)) {
								precachedCount++;
							}
						}
					}
					
					Log.d(TAG, "Precache completada: " + precachedCount + " assets, " +
					(precachedBytes.get() / 1024) + " KB");
					
					} catch (Exception e) {
					Log.e(TAG, "Precache error: " + e.getMessage());
				}
			}
		});
	}
	
	private boolean isCriticalAsset(String path) {
		if (path == null) return false;
		if (path.equals("/") || path.equals("/index.html")) return true;
		if (path.contains("/assets/index-") ||
		path.contains("/assets/vendor-") ||
		path.contains("/assets/main-") ||
		path.contains("/assets/runtime-")) return true;
		if (path.endsWith(".css") && !path.contains("/chunks/") && !path.contains("/pages/")) {
			String filename = path.substring(path.lastIndexOf('/') + 1);
			if (filename.startsWith("index") || filename.startsWith("main") ||
			filename.startsWith("app") || filename.startsWith("global")) return true;
		}
		if (path.endsWith(".js") && !path.contains("/chunks/") && !path.contains("/pages/")) {
			String filename = path.substring(path.lastIndexOf('/') + 1);
			if (filename.startsWith("index") || filename.startsWith("main") ||
			filename.startsWith("app")) return true;
		}
		return false;
	}
	
	private boolean precacheAsset(String path) {
		if (path == null || precachedAssets.contains(path)) return false;
		if (precachedAssets.size() >= MAX_PRECACHED_ASSETS) return false;
		if (precachedBytes.get() >= MAX_PRECACHED_BYTES) return false;
		
		try {
			if (!securityManager.isPathAllowed(path)) return false;
			
			PathHandler handler = findHandler(path);
			if (handler == null) return false;
			
			WebResourceResponse response = handler.handle(path, null);
			
			if (response != null && response.getData() instanceof ByteArrayInputStream) {
				int available = response.getData().available();
				
				cacheResponseWithHeaders(path, response, null);
				precachedAssets.add(path);
				precachedBytes.addAndGet(available);
				
				return true;
			}
			} catch (Exception e) {
			Log.e(TAG, "Precache fallo: " + path + " - " + e.getMessage());
		}
		return false;
	}
	
	public void setPrecacheEnabled(boolean enabled) {
		this.precacheEnabled = enabled;
		if (!enabled) {
			precachedAssets.clear();
			precachedBytes.set(0);
		}
	}
	
	public boolean isPrecacheEnabled() { return precacheEnabled; }
	
	public void preloadAsset(String path) {
		if (path == null) return;
		if (precacheEnabled && !precacheExecutor.isShutdown()) {
			precacheExecutor.execute(new Runnable() {
				@Override
				public void run() {
					String resolvedPath = path;
					AssetManifestEntry entry = assetManifest.resolve(path);
					if (entry != null) resolvedPath = entry.hashedPath;
					precacheAsset(resolvedPath);
				}
			});
		}
	}
	
	public void preloadAssets(String... paths) {
		if (paths == null) return;
		for (String path : paths) preloadAsset(path);
	}
	
	// ==================== CACHÉ CON HEADERS PRECALCULADOS ====================
	
	private void cacheResponseWithHeaders(String path, WebResourceResponse response,
	String integrityHash) {
		try {
			InputStream is = response.getData();
			if (!(is instanceof ByteArrayInputStream)) return;
			ByteArrayInputStream bais = (ByteArrayInputStream) is;
			
			int available = bais.available();
			if (available == 0 || available > MAX_CACHEABLE_READ_BYTES) {
				if (bais.markSupported()) bais.reset();
				return;
			}
			
			byte[] data = readFullyLimited(bais, MAX_CACHEABLE_READ_BYTES);
			if (data.length == 0) {
				if (bais.markSupported()) bais.reset();
				return;
			}
			
			String etag = CacheManager.generateETag(data);
			String mimeType = response.getMimeType();
			String encoding = response.getEncoding();
			long lastModified = System.currentTimeMillis();
			
			Map<String, String> precomputedHeaders = buildResponseHeaders(
			path, mimeType, etag, integrityHash);
			
			cacheManager.put(path, data, mimeType, encoding, precomputedHeaders, etag, lastModified);
			
			if (bais.markSupported()) {
				bais.reset();
			}
			
			} catch (IOException e) {
			Log.e(TAG, "Cache limit: " + path + " - " + e.getMessage());
			} catch (Exception e) {
			Log.e(TAG, "Cache error: " + path + " - " + e.getMessage());
		}
	}
	
	private Map<String, String> buildResponseHeaders(String path, String mimeType,
	String etag, String integrityHash) {
		Map<String, String> headers = new LinkedHashMap<>();
		
		headers.put("Content-Type", mimeType != null ? mimeType : "application/octet-stream");
		headers.put("Content-Security-Policy", cspPolicy);
		headers.put("X-Content-Type-Options", "nosniff");
		headers.put("X-Frame-Options", "DENY");
		headers.put("X-XSS-Protection", "1; mode=block");
		headers.put("Access-Control-Allow-Origin", "*");
		headers.put("ETag", etag);
		headers.put("Vary", "Accept-Encoding");
		
		if (integrityHash != null) {
			headers.put("Content-Integrity", integrityHash);
		}
		
		if (mimeType != null) {
			headers.put("Cache-Control", cachePolicy.getCacheControlHeader(mimeType));
		}
		
		return headers;
	}
	
	// ==================== ENRIQUECIMIENTO DE HEADERS ====================
	
	private WebResourceResponse enrichResponse(WebResourceResponse original, String path,
	String integrityHash) {
		if (original == null) return null;
		
		Map<String, String> enrichedHeaders = new LinkedHashMap<>();
		Map<String, String> originalHeaders = original.getResponseHeaders();
		if (mergeHeaders && originalHeaders != null) {
			enrichedHeaders.putAll(originalHeaders);
		}
		
		if (!enrichedHeaders.containsKey("Content-Security-Policy"))
		enrichedHeaders.put("Content-Security-Policy", cspPolicy);
		if (!enrichedHeaders.containsKey("X-Content-Type-Options"))
		enrichedHeaders.put("X-Content-Type-Options", "nosniff");
		if (!enrichedHeaders.containsKey("X-Frame-Options"))
		enrichedHeaders.put("X-Frame-Options", "DENY");
		if (!enrichedHeaders.containsKey("X-XSS-Protection"))
		enrichedHeaders.put("X-XSS-Protection", "1; mode=block");
		if (!enrichedHeaders.containsKey("Access-Control-Allow-Origin"))
		enrichedHeaders.put("Access-Control-Allow-Origin", "*");
		
		if (integrityHash != null && !enrichedHeaders.containsKey("Content-Integrity"))
		enrichedHeaders.put("Content-Integrity", integrityHash);
		
		String mimeType = original.getMimeType();
		if (mimeType != null) {
			if (!enrichedHeaders.containsKey("Cache-Control")) {
				enrichedHeaders.put("Cache-Control", cachePolicy.getCacheControlHeader(mimeType));
			}
		}
		
		if (!enrichedHeaders.containsKey("Vary"))
		enrichedHeaders.put("Vary", "Accept-Encoding");
		
		int statusCode = original.getStatusCode();
		if (statusCode < 100) statusCode = 200;
		String reasonPhrase = original.getReasonPhrase();
		if (reasonPhrase == null || reasonPhrase.isEmpty())
		reasonPhrase = getDefaultReasonPhrase(statusCode);
		String finalMimeType = original.getMimeType();
		if (finalMimeType == null || finalMimeType.isEmpty()) finalMimeType = "text/plain";
		String encoding = original.getEncoding();
		if (encoding == null || encoding.isEmpty()) encoding = "UTF-8";
		
		return new WebResourceResponse(finalMimeType, encoding, statusCode, reasonPhrase,
		enrichedHeaders, original.getData());
	}
	
	// ==================== RANGE REQUESTS ====================
	
	private WebResourceResponse handleRangeRequest(
	String path, String rangeHeader, WebResourceRequest request) {
		PathHandler handler = findHandler(path);
		if (handler == null) return null;
		
		try {
			if (handler instanceof FilePathHandler) {
				return ((FilePathHandler) handler).handleRange(path, rangeHeader, request);
			}
			if (handler instanceof AssetPathHandler) {
				return ((AssetPathHandler) handler).handleRange(path, rangeHeader, request);
			}
			
			WebResourceResponse fullResponse = handler.handle(path, request);
			if (fullResponse == null || fullResponse.getData() == null) return null;
			
			byte[] data;
			try {
				data = readFully(fullResponse.getData());
				} catch (OutOfMemoryError oom) {
				return fullResponse;
			}
			
			long fileSize = data.length;
			long[] range = RangeParser.parse(rangeHeader, fileSize);
			if (range == null) return create416Response(fileSize);
			
			long start = range[0], end = range[1];
			int contentLength = (int) (end - start + 1);
			
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
			headers.put("Content-Length", String.valueOf(contentLength));
			headers.put("Accept-Ranges", "bytes");
			headers.put("Content-Type", fullResponse.getMimeType());
			
			byte[] partialData = Arrays.copyOfRange(data, (int) start, (int) end + 1);
			
			return new WebResourceResponse(
			fullResponse.getMimeType(), fullResponse.getEncoding(),
			206, "Partial Content", headers, new ByteArrayInputStream(partialData)
			);
			} catch (Exception e) {
			return null;
		}
	}
	
	// ==================== UTILIDADES HTTP ====================
	
	private String getRequestHeader(WebResourceRequest request, String headerName) {
		Map<String, String> headers = request.getRequestHeaders();
		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(headerName)) return entry.getValue();
			}
		}
		return null;
	}
	
	private WebResourceResponse create416Response(long fileSize) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Range", "bytes */" + fileSize);
		headers.put("Content-Length", "0");
		return new WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable",
		headers, new ByteArrayInputStream(new byte[0]));
	}
	
	private WebResourceResponse createErrorResponse(int code, String message) {
		metricsCollector.recordHttpError();
		
		String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n"
		+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
		+ "<title>Error " + code + "</title>\n<style>\n"
		+ "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
		+ "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;"
		+ "background:#1a1a2e;color:#eee}\n"
		+ ".error-container{text-align:center;padding:2rem}\n"
		+ ".error-code{font-size:6rem;font-weight:700;color:#e74c3c;line-height:1;margin:0}\n"
		+ ".error-message{font-size:1rem;color:#999;margin-top:1rem}\n"
		+ ".error-footer{margin-top:2rem;padding-top:1rem;border-top:1px solid #333;"
		+ "font-size:.8rem;color:#555}\n"
		+ "</style>\n</head>\n<body>\n<div class=\"error-container\">\n"
		+ "<h1 class=\"error-code\">" + code + "</h1>\n"
		+ "<p class=\"error-message\">" + escapeHtml(message) + "</p>\n"
		+ "<div class=\"error-footer\">" + FULL + "</div>\n</div>\n</body>\n</html>";
		
		byte[] data = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/html; charset=utf-8");
		headers.put("Content-Length", String.valueOf(data.length));
		return new WebResourceResponse("text/html", "UTF-8", code, "Error", headers,
		new ByteArrayInputStream(data));
	}
	
	private String getDefaultReasonPhrase(int statusCode) {
		switch (statusCode) {
			case 200: return "OK";
			case 206: return "Partial Content";
			case 304: return "Not Modified";
			case 400: return "Bad Request";
			case 403: return "Forbidden";
			case 404: return "Not Found";
			case 416: return "Range Not Satisfiable";
			case 500: return "Internal Server Error";
			default: return "OK";
		}
	}
	
	// ==================== LECTURA DE STREAMS ====================
	
	private byte[] readFully(InputStream is) throws IOException {
		return readFullyLimited(is, MAX_CACHEABLE_READ_BYTES);
	}
	
	private byte[] readFullyLimited(InputStream is, int maxBytes) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		
		while ((read = is.read(buffer)) != -1) {
			total += read;
			if (total > maxBytes) {
				throw new IOException("Stream exceeds limit: " + maxBytes + " bytes (read: " + total + ")");
			}
			baos.write(buffer, 0, read);
		}
		
		return baos.toByteArray();
	}
	
	// ==================== UTILIDADES DE PATH ====================
	
	private boolean isCacheableStatic(String path) {
		String ext = getExtension(path);
		return ext != null && (
		ext.equals("css") || ext.equals("js") || ext.equals("mjs")
		|| ext.equals("json") || ext.equals("map")
		|| ext.equals("woff") || ext.equals("woff2")
		|| ext.equals("ttf") || ext.equals("otf")
		|| ext.equals("png") || ext.equals("svg")
		|| ext.equals("ico") || ext.equals("webmanifest")
		|| ext.equals("html") || ext.equals("htm")
		);
	}
	
	private PathHandler findHandler(String path) {
		PathHandler exact = handlers.get(path);
		if (exact != null) return exact;
		List<String> prefixes = sortedPrefixes;
		for (String prefix : prefixes) {
			if (path.startsWith(prefix)) return handlers.get(prefix);
		}
		return null;
	}
	
	private String extractSafePath(Uri uri) {
		String path = uri.getPath();
		if (path == null || path.isEmpty()) return "/";
		try {
			path = URLDecoder.decode(path, "UTF-8");
			path = sanitizePath(path);
			if (path == null) return null;
			return path;
		} catch (Exception e) { return null; }
	}
	
	private String sanitizePath(String path) {
		if (path == null) return null;
		path = path.replace('\\', '/');
		while (path.contains("//")) {
			path = path.replace("//", "/");
		}
		if (path.contains("..")) return null;
		if (!path.startsWith("/")) path = "/" + path;
		return path;
	}
	
	private String getExtension(String path) {
		int lastDot = path.lastIndexOf('.');
		return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : null;
	}
	
	// ==================== GESTIÓN DE CACHÉ Y MEMORIA ====================
	
	public void invalidateCache(String path) {
		cacheManager.remove(path);
		precachedAssets.remove(path);
	}
	
	public void clearCache() {
		cacheManager.clear();
		precachedAssets.clear();
		precachedBytes.set(0);
	}
	
	public void trimMemory(int level) {
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			cacheManager.clear();
			precachedAssets.clear();
			precachedBytes.set(0);
			} else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			cacheManager.trimToSize(maxCacheSizeBytes / 4);
			} else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
			cacheManager.trimToSize(maxCacheSizeBytes / 2);
		}
	}
	
	public int getCacheEntryCount() { return cacheManager.getEntryCount(); }
	public long getCacheSizeBytes() { return cacheManager.getCurrentSizeBytes(); }
	public int getPrecachedAssetCount() { return precachedAssets.size(); }
	public long getPrecachedBytes() { return precachedBytes.get(); }
	
	public double getCacheHitRate() { return cacheManager.getHitRate(); }
	public long getCacheHitCount() { return cacheManager.getHitCount(); }
	public long getCacheMissCount() { return cacheManager.getMissCount(); }
	public long getCacheEvictionCount() { return cacheManager.getEvictionCount(); }
	
	public void destroy() {
		precacheEnabled = false;
		if (precacheExecutor != null && !precacheExecutor.isShutdown()) {
			precacheExecutor.shutdown();
			try {
				if (!precacheExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
					precacheExecutor.shutdownNow();
				}
				} catch (InterruptedException e) {
				precacheExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		clearCache();
		inFlightRequests.clear();
	}
	
	// ==================== IN-FLIGHT REQUEST TRACKING ====================
	
	private static final class InFlightRequest {
		final Object lock = new Object();
		volatile boolean completed = false;
		volatile boolean cached = false;
	}
}