package com.webvirt;

import android.content.Context;
import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;

/**
* Interfaz para recolectar métricas de rendimiento de WebVirt.
* Permite inyectar una implementación personalizada.
*
* <h3>Implementación por defecto:</h3>
* Usa {@link WebVirtMetricsCollector#NOOP} cuando las métricas están desactivadas.
* Usa {@link WebVirtMetrics} como implementación con LoggingUtil.
*
* <h3>Ejemplo de uso:</h3>
* <pre>
* // Opción 1: Activar métricas por defecto
* WebVirt.with(context)
*     .withMetrics(true)
*     .bind(webView);
*
* // Opción 2: Collector personalizado
* WebVirt.with(context)
*     .withMetricsCollector(new FirebaseMetricsCollector())
*     .bind(webView);
*
* // Opción 3: Sin métricas (default, cero overhead)
* WebVirt.with(context)
*     .bind(webView);
* </pre>
*
* @see WebVirtMetrics Implementación por defecto
*/
public interface WebVirtMetricsCollector {
	
	/** Inicia una sesión de métricas */
	void startSession();
	
	/** Finaliza la sesión de métricas */
	void endSession();
	
	/** Registra la carga de un asset individual */
	void recordAssetLoad(String path, long loadTimeMs, boolean fromCache, long fileSize);
	
	/** Registra un error HTTP (4xx, 5xx) */
	void recordHttpError();
	
	/** Registra un SPA fallback (ruta → index.html) */
	void recordSpaFallback();
	
	/** Registra un Range Request (streaming de video/audio) */
	void recordRangeRequest();
	
	/** Genera reporte completo */
	String generateReport(@NonNull Context context);
	
	/** Genera resumen corto para UI o Toast */
	String generateSummary();
	
	/** Resetea todas las métricas */
	void reset();
	
	/** Obtiene las últimas cargas para análisis detallado */
	List<AssetLoadRecord> getRecentLoads();
	
	// ==================== CLASE DE DATOS ====================
	
	/**
	* Registro de carga de un asset individual.
	*/
	class AssetLoadRecord {
		public final String path;
		public final String mimeType;
		public final long loadTimeMs;
		public final boolean fromCache;
		public final long fileSize;
		public final long timestamp;
		
		public AssetLoadRecord(String path, String mimeType, long loadTimeMs,
		boolean fromCache, long fileSize) {
			this.path = path;
			this.mimeType = mimeType;
			this.loadTimeMs = loadTimeMs;
			this.fromCache = fromCache;
			this.fileSize = fileSize;
			this.timestamp = System.currentTimeMillis();
		}
		
		@Override
		public String toString() {
			return String.format("[%s] %s | %dms | %s | %d bytes",
			fromCache ? "CACHE" : "DISK",
			path,
			loadTimeMs,
			mimeType,
			fileSize
			);
		}
	}
	
	// ==================== INSTANCIA NoOp ====================
	
	/**
	* Instancia única que no hace nada (cero overhead).
	*
	* La JVM inlinea estas llamadas vacías en tiempo de ejecución,
	* resultando en exactamente CERO impacto de rendimiento.
	*
	* <h3>Uso:</h3>
	* <pre>
	* private WebVirtMetricsCollector metricsCollector = WebVirtMetricsCollector.NOOP;
	* </pre>
	*/
	WebVirtMetricsCollector NOOP = new WebVirtMetricsCollector() {
		@Override
		public void startSession() {}
		
		@Override
		public void endSession() {}
		
		@Override
		public void recordAssetLoad(String path, long loadTimeMs, boolean fromCache, long fileSize) {}
		
		@Override
		public void recordHttpError() {}
		
		@Override
		public void recordSpaFallback() {}
		
		@Override
		public void recordRangeRequest() {}
		
		@Override
		public String generateReport(@NonNull Context context) {
			return "Métricas desactivadas";
		}
		
		@Override
		public String generateSummary() {
			return "Métricas desactivadas";
		}
		
		@Override
		public void reset() {}
		
		@Override
		public List<AssetLoadRecord> getRecentLoads() {
			return Collections.emptyList();
		}
	};
}