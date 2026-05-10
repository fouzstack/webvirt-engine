package com.webvirt.loader;

import static com.webvirt.WebVirtResponses.escapeHtml;
import static com.webvirt.WebVirtVersion.FULL;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.webvirt.WebVirtMetricsCollector;

import java.io.*;
import java.net.URLDecoder;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
* WebVirtFileLoader v3.2.3 — Production Ready
*
* v3.2.3 Cambios:
* - cacheResponse() llama ANTES de enrichResponse() (stream preservado)
* - Métricas vía Strategy Pattern (WebVirtMetricsCollector)
* - WebVirtMetricsCollector.NOOP como default (cero overhead)
* - Sin logs de depuración (código limpio)
*/
public class WebVirtFileLoader {
	
	private static final String TAG = "WebVirtFileLoader";
	
	private final Context context;
	private final Map<String, PathHandler> handlers;
	private final List<String> sortedPrefixes;
	private final Object prefixLock = new Object();
	
	private final SecurityManager securityManager;
	private final CacheManager cacheManager;
	private final MimeTypeResolver mimeResolver;
	private final String allowedDomain;
	private final boolean debugMode;
	private final long maxCacheSizeBytes;
	@SuppressWarnings("unused")
	private final int cacheEntries;
	private final String cspPolicy;
	private final boolean mergeHeaders;
	
	private final WebVirtMetricsCollector metricsCollector;
	
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
		this.sortedPrefixes = new ArrayList<>();
		this.securityManager = new SecurityManager(
		builder.allowedExtensions, builder.maxFileSize);
		this.cacheManager = new CacheManager(builder.maxCacheSizeBytes, builder.cacheEntries);
		this.mimeResolver = new MimeTypeResolver();
		this.allowedDomain = builder.allowedDomain;
		this.debugMode = builder.debugMode;
		this.maxCacheSizeBytes = builder.maxCacheSizeBytes;
		this.cacheEntries = builder.cacheEntries;
		this.cspPolicy = builder.cspPolicy;
		this.mergeHeaders = builder.mergeHeaders;
		this.metricsCollector = builder.metricsCollector;
	}
	
	// ==================== BUILDER ====================
	
	public static class Builder {
		private final Context context;
		private String allowedDomain = "app.local";
		private Set<String> allowedExtensions;
		private boolean debugMode = false;
		private int cacheEntries = 200;
		private long maxFileSize = 10 * 1024 * 1024;
		private long maxCacheSizeBytes = 50 * 1024 * 1024;
		private boolean mergeHeaders = true;
		
		private String cspPolicy =
		"default-src 'self'; " +
		"script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
		"style-src 'self' 'unsafe-inline'; " +
		"img-src 'self' data: blob:; " +
		"font-src 'self' data:; " +
		"media-src 'self' data: blob:; " +
		"connect-src 'self' data: blob:;";
		
		private WebVirtMetricsCollector metricsCollector = WebVirtMetricsCollector.NOOP;
		
		public Builder(Context context) {
			this.context = context.getApplicationContext();
			this.allowedExtensions = SecurityManager.getDefaultAllowedExtensions();
		}
		
		public Builder setDomain(String domain) {
			this.allowedDomain = domain;
			return this;
		}
		
		public Builder setDebugMode(boolean debug) {
			this.debugMode = debug;
			return this;
		}
		
		public Builder setCacheEntries(int entries) {
			if (entries > 0) this.cacheEntries = entries;
			return this;
		}
		
		public Builder setMaxFileSize(long bytes) {
			if (bytes > 0) this.maxFileSize = bytes;
			return this;
		}
		
		public Builder setMaxCacheSize(long bytes) {
			if (bytes > 0) this.maxCacheSizeBytes = bytes;
			return this;
		}
		
		public Builder setCspPolicy(String csp) {
			if (csp != null && !csp.trim().isEmpty()) {
				this.cspPolicy = csp.trim();
			}
			return this;
		}
		
		public Builder setMergeHeaders(boolean merge) {
			this.mergeHeaders = merge;
			return this;
		}
		
		public Builder setAllowedExtensions(Set<String> extensions) {
			this.allowedExtensions = new HashSet<>(extensions);
			return this;
		}
		
		public Builder setMetricsCollector(WebVirtMetricsCollector collector) {
			if (collector != null) {
				this.metricsCollector = collector;
			}
			return this;
		}
		
		public WebVirtFileLoader build() {
			return new WebVirtFileLoader(this);
		}
	}
	
	// ==================== API PÚBLICA ====================
	
	public WebVirtFileLoader addPathHandler(String pathPrefix, PathHandler handler) {
		if (pathPrefix != null && handler != null) {
			handlers.put(pathPrefix, handler);
			
			synchronized (prefixLock) {
				if (!sortedPrefixes.contains(pathPrefix)) {
					sortedPrefixes.add(pathPrefix);
					sortedPrefixes.sort((a, b) -> Integer.compare(b.length(), a.length()));
				}
			}
			
			log("📁 Handler registrado: " + pathPrefix);
		}
		return this;
	}
	
	public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
		long startTime = SystemClock.elapsedRealtime();
		boolean fromCache = false;
		long fileSize = 0;
		
		Uri uri = request.getUrl();
		
		String scheme = uri.getScheme();
		if (!"https".equals(scheme) && !"http".equals(scheme)) {
			return null;
		}
		
		String host = uri.getHost();
		if (!allowedDomain.equals(host)) {
			return null;
		}
		
		String path = extractSafePath(uri);
		if (path == null) {
			log("⛔ Path inseguro: " + uri);
			return null;
		}
		
		if (!securityManager.isPathAllowed(path)) {
			log("⛔ Extensión no permitida: " + path);
			return null;
		}
		
		String rangeHeader      = getRequestHeader(request, "Range");
		String ifNoneMatch      = getRequestHeader(request, "If-None-Match");
		String ifModifiedSince  = getRequestHeader(request, "If-Modified-Since");
		
		if (rangeHeader != null) {
			metricsCollector.recordRangeRequest();
			return handleRangeRequest(path, rangeHeader, request);
		}
		
		if (isCacheableStatic(path)) {
			CacheManager.CacheEntry cached = cacheManager.getEntry(path);
			if (cached != null) {
				if (ifModifiedSince != null) {
					try {
						Date ifModifiedDate = HTTP_DATE_FORMAT.get().parse(ifModifiedSince);
						if (cached.lastModified <= ifModifiedDate.getTime()) {
							log("📅 304 Not Modified (If-Modified-Since): " + path);
							metricsCollector.recordAssetLoad(path,
							SystemClock.elapsedRealtime() - startTime, true, 0);
							return create304Response(cached.etag, cached.lastModified);
						}
						} catch (ParseException e) {
						// Fecha inválida, ignorar
					}
				}
				
				if (ifNoneMatch != null && ifNoneMatch.equals(cached.etag)) {
					log("🏷️ 304 Not Modified (ETag): " + path);
					metricsCollector.recordAssetLoad(path,
					SystemClock.elapsedRealtime() - startTime, true, 0);
					return create304Response(cached.etag, cached.lastModified);
				}
				
				log("💾 Cache hit: " + path);
				fromCache = true;
				
				WebResourceResponse response = cached.toResponse();
				if (response != null) {
					fileSize = cached.data != null ? cached.data.length : 0;
					metricsCollector.recordAssetLoad(path,
					SystemClock.elapsedRealtime() - startTime, true, fileSize);
				}
				return response;
			}
		}
		
		PathHandler handler = findHandler(path);
		if (handler == null) {
			return null;
		}
		
		try {
			WebResourceResponse response = handler.handle(path, request);
			
			if (response != null) {
				if (isCacheableStatic(path)
				&& !fromCache
				&& response.getData() instanceof ByteArrayInputStream) {
					
					ByteArrayInputStream bais = (ByteArrayInputStream) response.getData();
					try {
						fileSize = bais.available();
						
					} catch (Exception e) {
					log("❌ Error cargando: " + path + " - " + e.getMessage());
					metricsCollector.recordHttpError();
					return createErrorResponse(500, "Internal Server Error");
				}
					
					cacheResponse(path, response);
				}
				
				response = enrichResponse(response, path);
				
				if (!fromCache && fileSize == 0 && response.getData() instanceof ByteArrayInputStream) {
					try {
						fileSize = response.getData().available();
						} catch (IOException e) {
						// Ignorar
					}
				}
				
				metricsCollector.recordAssetLoad(path,
				SystemClock.elapsedRealtime() - startTime, false, fileSize);
			}
			
			return response;
			} catch (Exception e) {
			log("❌ Error cargando: " + path + " - " + e.getMessage());
			metricsCollector.recordHttpError();
			return createErrorResponse(500, "Internal Server Error");
		}
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
				log("❌ OOM en Range request fallback: " + path + " — sirviendo completo");
				return fullResponse;
			}
			
			if (data.length > 10 * 1024 * 1024) {
				log("⚠️ Archivo muy grande para Range request: " + path);
				return new WebResourceResponse(
				fullResponse.getMimeType(),
				fullResponse.getEncoding(),
				200, "OK",
				fullResponse.getResponseHeaders(),
				new ByteArrayInputStream(data)
				);
			}
			
			long fileSize = data.length;
			long[] range = RangeParser.parse(rangeHeader, fileSize);
			if (range == null) {
				return create416Response(fileSize);
			}
			
			long start = range[0];
			long end   = range[1];
			int contentLength = (int) (end - start + 1);
			
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Content-Range",
			"bytes " + start + "-" + end + "/" + fileSize);
			headers.put("Content-Length", String.valueOf(contentLength));
			headers.put("Accept-Ranges", "bytes");
			headers.put("Content-Type", fullResponse.getMimeType());
			
			byte[] partialData = Arrays.copyOfRange(data, (int) start, (int) end + 1);
			
			return new WebResourceResponse(
			fullResponse.getMimeType(),
			fullResponse.getEncoding(),
			206, "Partial Content",
			headers,
			new ByteArrayInputStream(partialData)
			);
			
			} catch (Exception e) {
			log("❌ Error en Range request: " + e.getMessage());
			return null;
		}
	}
	
	// ==================== ENRIQUECIMIENTO DE HEADERS ====================
	
	private WebResourceResponse enrichResponse(WebResourceResponse original, String path) {
		if (original == null) return null;
		
		Map<String, String> enrichedHeaders = new LinkedHashMap<>();
		
		Map<String, String> originalHeaders = original.getResponseHeaders();
		if (mergeHeaders && originalHeaders != null) {
			enrichedHeaders.putAll(originalHeaders);
		}
		
		if (!enrichedHeaders.containsKey("Content-Security-Policy")) {
			enrichedHeaders.put("Content-Security-Policy", cspPolicy);
		}
		if (!enrichedHeaders.containsKey("X-Content-Type-Options")) {
			enrichedHeaders.put("X-Content-Type-Options", "nosniff");
		}
		if (!enrichedHeaders.containsKey("X-Frame-Options")) {
			enrichedHeaders.put("X-Frame-Options", "DENY");
		}
		if (!enrichedHeaders.containsKey("X-XSS-Protection")) {
			enrichedHeaders.put("X-XSS-Protection", "1; mode=block");
		}
		if (!enrichedHeaders.containsKey("Access-Control-Allow-Origin")) {
			enrichedHeaders.put("Access-Control-Allow-Origin", "*");
		}
		
		if (!enrichedHeaders.containsKey("Content-Length")) {
			InputStream data = original.getData();
			if (data instanceof ByteArrayInputStream) {
				try {
					int available = data.available();
					if (available > 0) {
						enrichedHeaders.put("Content-Length", String.valueOf(available));
					}
					} catch (IOException e) {
					// Ignorar
				}
			}
		}
		
		int statusCode = original.getStatusCode();
		if (statusCode < 100) {
			statusCode = 200;
		}
		
		String reasonPhrase = original.getReasonPhrase();
		if (reasonPhrase == null || reasonPhrase.isEmpty()) {
			reasonPhrase = getDefaultReasonPhrase(statusCode);
		}
		
		String mimeType = original.getMimeType();
		if (mimeType == null || mimeType.isEmpty()) {
			mimeType = "text/plain";
		}
		
		String encoding = original.getEncoding();
		if (encoding == null || encoding.isEmpty()) {
			encoding = "UTF-8";
		}
		
		return new WebResourceResponse(
		mimeType,
		encoding,
		statusCode,
		reasonPhrase,
		enrichedHeaders,
		original.getData()
		);
	}
	
	private String getDefaultReasonPhrase(int statusCode) {
		switch (statusCode) {
			case 200: return "OK";
			case 201: return "Created";
			case 204: return "No Content";
			case 206: return "Partial Content";
			case 301: return "Moved Permanently";
			case 302: return "Found";
			case 304: return "Not Modified";
			case 400: return "Bad Request";
			case 401: return "Unauthorized";
			case 403: return "Forbidden";
			case 404: return "Not Found";
			case 405: return "Method Not Allowed";
			case 416: return "Range Not Satisfiable";
			case 500: return "Internal Server Error";
			case 502: return "Bad Gateway";
			case 503: return "Service Unavailable";
			default: return "OK";
		}
	}
	
	// ==================== CACHÉ ====================
	
	private void cacheResponse(String path, WebResourceResponse response) {
		try {
			InputStream is = response.getData();
			if (is == null) return;
			
			if (!(is instanceof ByteArrayInputStream)) {
				log("⚠️ No se cachea streaming: " + path);
				return;
			}
			
			ByteArrayInputStream bais = (ByteArrayInputStream) is;
			
			byte[] data = readFully(bais);
			
			if (data.length == 0) {
				log("⚠️ Stream vacío, no se cachea: " + path);
				return;
			}
			
			if (data.length > 5 * 1024 * 1024) {
				log("⚠️ Archivo muy grande para cache: " + path + " (" + data.length + " bytes)");
				bais.reset();
				return;
			}
			
			String etag = generateETag(data);
			long lastModified = System.currentTimeMillis();
			
			cacheManager.put(path, data, response.getMimeType(),
			response.getEncoding(), etag, lastModified);
			
			log("💾 Cached: " + path + " (" + data.length + " bytes)");
			
			bais.reset();
			
			} catch (Exception e) {
			log("⚠️ Error cacheando: " + path + " — " + e.getMessage());
			try {
				InputStream is = response.getData();
				if (is != null) is.reset();
			} catch (IOException ignored) {}
		}
	}
	
	// ==================== UTILIDADES HTTP ====================
	
	private String generateETag(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] digest = md.digest(data);
			return "\"" + Base64.encodeToString(digest, Base64.NO_WRAP) + "\"";
			} catch (NoSuchAlgorithmException e) {
			return "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
		}
	}
	
	private WebResourceResponse create304Response(String etag, long lastModified) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("ETag", etag);
		headers.put("Last-Modified",
		HTTP_DATE_FORMAT.get().format(new Date(lastModified)));
		headers.put("Cache-Control", "public, max-age=3600");
		headers.put("Content-Length", "0");
		
		return new WebResourceResponse(
		"text/plain", "UTF-8", 304, "Not Modified",
		headers, new ByteArrayInputStream(new byte[0])
		);
	}
	
	private WebResourceResponse create416Response(long fileSize) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Range", "bytes */" + fileSize);
		headers.put("Content-Length", "0");
		
		return new WebResourceResponse(
		"text/plain", "UTF-8", 416, "Range Not Satisfiable",
		headers, new ByteArrayInputStream(new byte[0])
		);
	}
	
	private WebResourceResponse createErrorResponse(int code, String message) {
		metricsCollector.recordHttpError();
		
		String html =
		"<!DOCTYPE html>\n" +
		"<html lang=\"en\">\n" +
		"<head>\n" +
		"<meta charset=\"UTF-8\">\n" +
		"<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
		"<title>Error " + code + "</title>\n" +
		"<style>\n" +
		"body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
		"display: flex; align-items: center; justify-content: center; " +
		"height: 100vh; margin: 0; background: #1a1a2e; color: #eee; }\n" +
		".error-container { text-align: center; padding: 2rem; }\n" +
		".error-code { font-size: 6rem; font-weight: 700; color: #e74c3c; " +
		"line-height: 1; margin: 0; }\n" +
		".error-message { font-size: 1rem; color: #999; margin-top: 1rem; }\n" +
		".error-footer { margin-top: 2rem; padding-top: 1rem; " +
		"border-top: 1px solid #333; font-size: 0.8rem; color: #555; }\n" +
		"</style>\n" +
		"</head>\n" +
		"<body>\n" +
		"<div class=\"error-container\">\n" +
		"<h1 class=\"error-code\">" + code + "</h1>\n" +
		"<p class=\"error-message\">" + escapeHtml(message) + "</p>\n" +
		"<div class=\"error-footer\">" + FULL + "</div>\n" +
		"</div>\n" +
		"</body>\n" +
		"</html>";
		
		byte[] data = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "text/html; charset=utf-8");
		headers.put("Content-Length", String.valueOf(data.length));
		headers.put("X-Content-Type-Options", "nosniff");
		
		return new WebResourceResponse(
		"text/html", "UTF-8", code, "Error",
		headers, new ByteArrayInputStream(data)
		);
	}
	
	// ==================== UTILIDADES INTERNAS ====================
	
	private String getRequestHeader(WebResourceRequest request, String headerName) {
		Map<String, String> headers = request.getRequestHeaders();
		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(headerName)) {
					return entry.getValue();
				}
			}
		}
		return null;
	}
	
	private byte[] readFully(InputStream is) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int bytesRead;
		while ((bytesRead = is.read(buffer)) != -1) {
			baos.write(buffer, 0, bytesRead);
		}
		return baos.toByteArray();
	}
	
	private boolean isCacheableStatic(String path) {
		String ext = getExtension(path);
		return ext != null && (
		ext.equals("css")         ||
		ext.equals("js")          ||
		ext.equals("mjs")         ||
		ext.equals("json")        ||
		ext.equals("map")         ||
		ext.equals("woff")        ||
		ext.equals("woff2")       ||
		ext.equals("ttf")         ||
		ext.equals("otf")         ||
		ext.equals("png")         ||
		ext.equals("svg")         ||
		ext.equals("ico")         ||
		ext.equals("webmanifest") ||
		ext.equals("html")        ||
		ext.equals("htm")
		);
	}
	
	private PathHandler findHandler(String path) {
		PathHandler exact = handlers.get(path);
		if (exact != null) return exact;
		
		synchronized (prefixLock) {
			for (String prefix : sortedPrefixes) {
				if (path.startsWith(prefix)) {
					return handlers.get(prefix);
				}
			}
		}
		return null;
	}
	
	private String extractSafePath(Uri uri) {
		String path = uri.getPath();
		if (path == null || path.isEmpty()) return "/";
		
		try {
			path = URLDecoder.decode(path, "UTF-8");
			path = Paths.get(path).normalize().toString();
			
			if (path.contains("..") || !path.startsWith("/")) {
				return null;
			}
			
			return path;
			} catch (Exception e) {
			return null;
		}
	}
	
	private String getExtension(String path) {
		int lastDot = path.lastIndexOf('.');
		return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : null;
	}
	
	// ==================== GESTIÓN DE CACHÉ Y MEMORIA ====================
	
	public void invalidateCache(String path) {
		cacheManager.remove(path);
	}
	
	public void clearCache() {
		cacheManager.clear();
	}
	
	public void trimMemory(int level) {
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			cacheManager.clear();
			log("🧹 Cache completely cleared (critical memory pressure)");
			} else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			cacheManager.trimToSize(maxCacheSizeBytes / 4);
			log("🧹 Cache trimmed to 25% (low memory)");
			} else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
			cacheManager.trimToSize(maxCacheSizeBytes / 2);
			log("🧹 Cache trimmed to 50% (moderate memory pressure)");
		}
	}
	
	public int getCacheEntryCount() {
		return cacheManager.getEntryCount();
	}
	
	public long getCacheSizeBytes() {
		return cacheManager.getCurrentSizeBytes();
	}
	
	private void log(String msg) {
		if (debugMode) {
			Log.d(TAG, msg);
		}
	}
}