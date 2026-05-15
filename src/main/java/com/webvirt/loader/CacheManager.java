package com.webvirt.loader;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
* CacheManager v3.5.2 — Production Optimized
*
* MEJORAS SOBRE v3.1.1:
* - CacheEntry con responseHeaders precalculados (evita recalcular headers en warm loads)
* - ETag con CRC32 ultra-rápido (10-50x más rápido que SHA-1)
* - LRU manual con LinkedHashMap (más control que LruCache de Android)
* - Evicción precisa por bytes y entradas
* - Thread-safe con ConcurrentHashMap + locks finos
* - Soporte para respuestas 304 Not Modified desde caché
* - Métricas de caché (hit rate, evicciones)
*
* COMPATIBILIDAD HACIA ATRÁS:
* - put(key, data, mimeType, encoding, etag, lastModified) se mantiene
* - getEntry(key) se mantiene
* - toResponse() se mantiene (con NPE protection)
* - clear(), remove(), trimToSize() se mantienen
*
* @since 3.5.2
*/
public class CacheManager {
	
	// Almacenamiento principal thread-safe
	private final ConcurrentHashMap<String, CacheEntry> cache;
	
	// LRU tracker para política de evicción
	private final LinkedHashMap<String, Long> lruTracker;
	private final Object lruLock = new Object();
	
	// Límites configurables
	private final int maxEntries;
	private final long maxSizeBytes;
	
	// Tamaño actual (thread-safe)
	private final AtomicLong currentSizeBytes;
	
	// TTL por defecto para entradas de caché
	private static final long DEFAULT_TTL_MILLIS = 60 * 60 * 1000; // 1 hora
	
	// Métricas internas
	private final AtomicLong hitCount = new AtomicLong(0);
	private final AtomicLong missCount = new AtomicLong(0);
	private final AtomicLong evictionCount = new AtomicLong(0);
	
	/**
	* Constructor compatible con v3.1.1
	*/
	public CacheManager(long maxSizeBytes, int maxEntries) {
		this.maxSizeBytes = maxSizeBytes;
		this.maxEntries = maxEntries > 0 ? maxEntries : 200;
		this.cache = new ConcurrentHashMap<>(this.maxEntries);
		this.lruTracker = new LinkedHashMap<>(16, 0.75f, true);
		this.currentSizeBytes = new AtomicLong(0);
	}
	
	// ==================== API PÚBLICA (COMPATIBLE HACIA ATRÁS) ====================
	
	/**
	* v3.5.2: CacheEntry inmutable con headers de respuesta precalculados.
	*
	* BENEFICIO: En warm loads, el WebResourceResponse se sirve directamente
	* sin necesidad de recalcular CSP, CORS, X-Content-Type-Options, etc.
	*
	* COMPATIBILIDAD: El constructor antiguo sin responseHeaders sigue funcionando.
	*/
	public static class CacheEntry {
		public final byte[] data;
		public final String mimeType;
		public final String encoding;
		public final Map<String, String> responseHeaders;
		public final String etag;
		public final long lastModified;
		public final long createdAt;
		public final long size;
		
		private final long ttlMillis;
		
		/**
		* Constructor NUEVO con responseHeaders precalculados.
		*/
		CacheEntry(byte[] data, String mimeType, String encoding,
		Map<String, String> responseHeaders, String etag, long lastModified) {
			this.data = data;
			this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
			this.encoding = encoding != null ? encoding : "UTF-8";
			// Defensa: copia inmutable de headers
			this.responseHeaders = responseHeaders != null
			? Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders))
			: null;
			this.etag = etag;
			this.lastModified = lastModified;
			this.createdAt = System.currentTimeMillis();
			this.size = data != null ? data.length : 0;
			this.ttlMillis = DEFAULT_TTL_MILLIS;
		}
		
		/**
		* Constructor COMPATIBLE con v3.1.1 (sin responseHeaders).
		* Se mantiene para no romper código existente.
		*/
		CacheEntry(byte[] data, String mimeType, String encoding,
		String etag, long lastModified) {
			this(data, mimeType, encoding, null, etag, lastModified);
		}
		
		boolean isExpired() {
			return System.currentTimeMillis() - createdAt > ttlMillis;
		}
		
		/**
		* Construye WebResourceResponse desde caché.
		*
		* v3.5.2: Si responseHeaders está disponible, los usa directamente
		* (evita recalcular headers de seguridad en cada warm load).
		* Si no, construye headers mínimos (compatibilidad hacia atrás).
		*/
		public WebResourceResponse toResponse() {
			if (data == null) return null;
			
			Map<String, String> headers;
			
			if (responseHeaders != null && !responseHeaders.isEmpty()) {
				// v3.5.2: Usar headers precalculados (warm load optimizado)
				headers = new LinkedHashMap<>(responseHeaders);
				// Asegurar que Content-Length esté actualizado
				headers.put("Content-Length", String.valueOf(data.length));
				if (!headers.containsKey("ETag")) {
					headers.put("ETag", etag);
				}
				} else {
				// Compatibilidad hacia atrás: headers mínimos
				headers = new LinkedHashMap<>();
				headers.put("Content-Type", mimeType);
				headers.put("ETag", etag);
				headers.put("Cache-Control", "public, max-age=3600");
				headers.put("Content-Length", String.valueOf(data.length));
			}
			
			return new WebResourceResponse(
			mimeType,
			encoding,
			200,
			"OK",
			headers,
			new ByteArrayInputStream(data)
			);
		}
		
		/**
		* v3.5.2: Construye respuesta 304 Not Modified.
		*/
		public WebResourceResponse to304Response() {
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("ETag", etag);
			headers.put("Cache-Control", "public, max-age=3600");
			headers.put("Content-Length", "0");
			return new WebResourceResponse(
			"text/plain", "UTF-8",
			304, "Not Modified",
			headers,
			new ByteArrayInputStream(new byte[0])
			);
		}
		
		/**
		* Obtiene un header específico (si está precalculado).
		*/
		public String getHeader(String name) {
			if (responseHeaders != null) {
				return responseHeaders.get(name);
			}
			return null;
		}
	}
	
	// ==================== MÉTODOS DE ALMACENAMIENTO ====================
	
	/**
	* v3.5.2: Almacena con headers precalculados (NUEVO).
	*/
	public void put(String path, byte[] data, String mimeType, String encoding,
	Map<String, String> responseHeaders, String etag, long lastModified) {
		if (path == null || data == null || data.length == 0) return;
		
		long dataSize = data.length;
		
		// Rechazar assets demasiado grandes para caché
		if (dataSize > maxSizeBytes / 10) {
			return;
		}
		
		CacheEntry entry = new CacheEntry(data, mimeType, encoding, responseHeaders, etag, lastModified);
		
		// Verificar si ya existe y calcular delta de tamaño
		CacheEntry oldEntry = cache.put(path, entry);
		long sizeDelta = dataSize;
		if (oldEntry != null) {
			sizeDelta -= oldEntry.size;
		}
		
		long newSize = currentSizeBytes.addAndGet(sizeDelta);
		
		// Actualizar LRU tracker
		synchronized (lruLock) {
			lruTracker.put(path, dataSize);
		}
		
		// Evicción si se exceden límites
		if (cache.size() > maxEntries || newSize > maxSizeBytes) {
			evictIfNeeded();
		}
	}
	
	/**
	* v3.5.2: Método COMPATIBLE con API antigua (sin responseHeaders).
	*/
	public void put(String path, byte[] data, String mimeType, String encoding,
	String etag, long lastModified) {
		put(path, data, mimeType, encoding, null, etag, lastModified);
	}
	
	/**
	* v3.5.2: Obtiene entrada del caché con tracking de hit/miss.
	*/
	public CacheEntry getEntry(String path) {
		CacheEntry entry = cache.get(path);
		
		if (entry != null) {
			if (!entry.isExpired()) {
				// Hit: actualizar LRU y contador
				synchronized (lruLock) {
					lruTracker.get(path);
				}
				hitCount.incrementAndGet();
				return entry;
				} else {
				// Expirado: eliminar
				cache.remove(path);
				currentSizeBytes.addAndGet(-entry.size);
				synchronized (lruLock) {
					lruTracker.remove(path);
				}
			}
		}
		
		missCount.incrementAndGet();
		return null;
	}
	
	/**
	* v3.5.2: Obtiene respuesta cacheada (compatible con API antigua).
	*/
	public WebResourceResponse get(String path) {
		CacheEntry entry = getEntry(path);
		if (entry != null) {
			WebResourceResponse response = entry.toResponse();
			if (response == null) {
				// Entrada corrupta, eliminar
				remove(path);
				return null;
			}
			return response;
		}
		return null;
	}
	
	/**
	* Elimina entrada del caché.
	*/
	public void remove(String path) {
		CacheEntry entry = cache.remove(path);
		if (entry != null) {
			currentSizeBytes.addAndGet(-entry.size);
		}
		synchronized (lruLock) {
			lruTracker.remove(path);
		}
	}
	
	/**
	* Limpia todo el caché.
	*/
	public void clear() {
		cache.clear();
		currentSizeBytes.set(0);
		synchronized (lruLock) {
			lruTracker.clear();
		}
		hitCount.set(0);
		missCount.set(0);
		evictionCount.set(0);
	}
	
	/**
	* Reduce el caché al tamaño objetivo.
	*/
	public void trimToSize(long targetSizeBytes) {
		while (currentSizeBytes.get() > targetSizeBytes && !cache.isEmpty()) {
			if (!evictOne()) break;
		}
	}
	
	// ==================== GETTERS ====================
	
	public int getEntryCount() {
		return cache.size();
	}
	
	public long getCurrentSizeBytes() {
		return currentSizeBytes.get();
	}
	
	public long getMaxSizeBytes() {
		return maxSizeBytes;
	}
	
	public int getMaxEntries() {
		return maxEntries;
	}
	
	/**
	* v3.5.2: Tasa de aciertos del caché.
	*/
	public double getHitRate() {
		long hits = hitCount.get();
		long total = hits + missCount.get();
		return total > 0 ? (double) hits / total : 0.0;
	}
	
	/**
	* v3.5.2: Total de evicciones realizadas.
	*/
	public long getEvictionCount() {
		return evictionCount.get();
	}
	
	/**
	* v3.5.2: Total de hits.
	*/
	public long getHitCount() {
		return hitCount.get();
	}
	
	/**
	* v3.5.2: Total de misses.
	*/
	public long getMissCount() {
		return missCount.get();
	}
	
	// ==================== UTILIDADES ESTÁTICAS ====================
	
	/**
	* v3.5.2: Genera ETag usando CRC32 (ultra-rápido).
	*/
	public static String generateETag(byte[] data) {
		if (data == null || data.length == 0) {
			return "\"0\"";
		}
		CRC32 crc = new CRC32();
		crc.update(data);
		return "\"" + Long.toHexString(crc.getValue()) + "\"";
	}
	
	/**
	* v3.5.2: Genera ETag para rango parcial.
	*/
	public static String generateETag(byte[] data, int offset, int length) {
		if (data == null || length == 0) {
			return "\"0\"";
		}
		CRC32 crc = new CRC32();
		crc.update(data, offset, length);
		return "\"" + Long.toHexString(crc.getValue()) + "\"";
	}
	
	// ==================== INTERNO ====================
	
	/**
	* Evicción cuando se exceden límites.
	*/
	private void evictIfNeeded() {
		int evicted = 0;
		while ((cache.size() > maxEntries || currentSizeBytes.get() > maxSizeBytes)
		&& !cache.isEmpty()) {
			if (!evictOne()) break;
			evicted++;
		}
		if (evicted > 0) {
			evictionCount.addAndGet(evicted);
		}
	}
	
	/**
	* Evicción de una sola entrada (la más antigua según LRU).
	*
	* CORREGIDO: No intenta asignar null a CacheEntry.data (que es final).
	* La entrada se elimina del mapa y el GC se encarga del resto.
	*
	* @return true si se eliminó una entrada
	*/
	private boolean evictOne() {
		synchronized (lruLock) {
			Iterator<Map.Entry<String, Long>> it = lruTracker.entrySet().iterator();
			if (!it.hasNext()) return false;
			
			Map.Entry<String, Long> oldest = it.next();
			String keyToRemove = oldest.getKey();
			it.remove();
			
			CacheEntry removed = cache.remove(keyToRemove);
			if (removed != null) {
				currentSizeBytes.addAndGet(-removed.size);
				// El GC liberará la memoria automáticamente
				// No se necesita: removed.data = null (data es final)
				return true;
			}
			return false;
		}
	}
	
	/**
	* Ajusta el caché para hacer espacio (método legacy, mantenido).
	*/
	private void trimToFit(int neededBytes) {
		int entriesToRemove = Math.max(5, (int) Math.ceil((double) neededBytes / 1024) + 10);
		for (int i = 0; i < entriesToRemove && !cache.isEmpty(); i++) {
			evictOne();
		}
		evictionCount.addAndGet(entriesToRemove);
	}
}