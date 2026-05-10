package com.webvirt;

import android.content.Context;
import android.os.SystemClock;

import com.webvirt.utils.LoggingUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementación por defecto de las métricas de WebVirt.
 * Usa LoggingUtil para guardar el reporte en Documents.
 * 
 * <h3>Activación:</h3>
 * <pre>
 * WebVirt.with(context)
 *     .withMetrics(true)   // Activa esta implementación
 *     .bind(webView);
 * </pre>
 * 
 * <h3>Uso manual:</h3>
 * <pre>
 * WebVirtMetrics metrics = new WebVirtMetrics();
 * WebVirt.with(context)
 *     .withMetricsCollector(metrics)
 *     .bind(webView);
 * // Más tarde:
 * String report = metrics.generateReport(context);
 * </pre>
 */
public class WebVirtMetrics implements WebVirtMetricsCollector {

    private static final int MAX_RECENT_LOADS = 200;

    // ==================== ESTADO (todo de instancia, sin static) ====================

    private long sessionStartTime = 0;
    private long sessionEndTime = 0;
    private long totalBytesLoaded = 0;
    private long totalBytesFromCache = 0;
    private int totalCacheHits = 0;
    private int totalCacheMisses = 0;
    private int totalAssetsLoaded = 0;
    private long totalLoadTimeMs = 0;
    private long minLoadTimeMs = Long.MAX_VALUE;
    private long maxLoadTimeMs = 0;
    private int httpErrors = 0;
    private int spaFallbacks = 0;
    private int rangeRequests = 0;

    // Contadores por tipo MIME
    private final Map<String, Integer> assetsByType = new LinkedHashMap<>();
    private final Map<String, Long> loadTimeByType = new LinkedHashMap<>();

    // Últimas cargas para análisis detallado
    private final List<AssetLoadRecord> recentLoads =
        Collections.synchronizedList(new ArrayList<>());

    // ==================== API PÚBLICA ====================

    @Override
    public void startSession() {
        reset();
        sessionStartTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void endSession() {
        sessionEndTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void recordAssetLoad(String path, long loadTimeMs,
                                boolean fromCache, long fileSize) {
        totalAssetsLoaded++;
        totalLoadTimeMs += loadTimeMs;
        totalBytesLoaded += fileSize;

        if (fromCache) {
            totalCacheHits++;
            totalBytesFromCache += fileSize;
        } else {
            totalCacheMisses++;
        }

        // Min/Max
        if (loadTimeMs < minLoadTimeMs) minLoadTimeMs = loadTimeMs;
        if (loadTimeMs > maxLoadTimeMs) maxLoadTimeMs = loadTimeMs;

        // Por tipo MIME
        String mimeType = extractMimeType(path);
        synchronized (assetsByType) {
            assetsByType.merge(mimeType, 1, Integer::sum);
            loadTimeByType.merge(mimeType, loadTimeMs, Long::sum);
        }

        // Registro detallado
        synchronized (recentLoads) {
            recentLoads.add(new AssetLoadRecord(path, mimeType, loadTimeMs, fromCache, fileSize));
            if (recentLoads.size() > MAX_RECENT_LOADS) {
                recentLoads.remove(0);
            }
        }
    }

    @Override
    public void recordHttpError() {
        httpErrors++;
    }

    @Override
    public void recordSpaFallback() {
        spaFallbacks++;
    }

    @Override
    public void recordRangeRequest() {
        rangeRequests++;
    }

    // ==================== REPORTE ====================

    @Override
    public String generateReport(Context context) {
        StringBuilder report = new StringBuilder();

        long sessionDuration = getSessionDuration();

        report.append("\n");
        report.append("╔══════════════════════════════════════════════════╗\n");
        report.append("║     WEBVIRT ENGINE - PERFORMANCE REPORT          ║\n");
        report.append("╠══════════════════════════════════════════════════╣\n");

        // Tiempos
        report.append(String.format("║ Session duration:     %8d ms              ║\n", sessionDuration));
        report.append(String.format("║ Total assets loaded:  %8d                  ║\n", totalAssetsLoaded));
        report.append(String.format("║ Total load time:      %8d ms              ║\n", totalLoadTimeMs));
        report.append(String.format("║ Avg load time:        %8d ms              ║\n",
            totalAssetsLoaded > 0 ? totalLoadTimeMs / totalAssetsLoaded : 0));
        report.append(String.format("║ Min load time:        %8d ms              ║\n",
            minLoadTimeMs != Long.MAX_VALUE ? minLoadTimeMs : 0));
        report.append(String.format("║ Max load time:        %8d ms              ║\n", maxLoadTimeMs));
        report.append("╠══════════════════════════════════════════════════╣\n");

        // Caché
        double cacheHitRate = totalAssetsLoaded > 0 ?
            (double) totalCacheHits / totalAssetsLoaded * 100 : 0;
        report.append(String.format("║ Cache hits:           %8d                  ║\n", totalCacheHits));
        report.append(String.format("║ Cache misses:         %8d                  ║\n", totalCacheMisses));
        report.append(String.format("║ Cache hit rate:       %7.1f%%                 ║\n", cacheHitRate));
        report.append(String.format("║ Bytes from cache:     %8d bytes           ║\n", totalBytesFromCache));
        report.append(String.format("║ Total bytes loaded:   %8d bytes           ║\n", totalBytesLoaded));

        if (totalBytesLoaded > 0) {
            double cacheByteRate = (double) totalBytesFromCache / totalBytesLoaded * 100;
            report.append(String.format("║ Cache byte rate:      %7.1f%%                 ║\n", cacheByteRate));
        }
        report.append("╠══════════════════════════════════════════════════╣\n");

        // Errores y fallbacks
        report.append(String.format("║ HTTP errors:          %8d                  ║\n", httpErrors));
        report.append(String.format("║ SPA fallbacks:        %8d                  ║\n", spaFallbacks));
        report.append(String.format("║ Range requests:       %8d                  ║\n", rangeRequests));
        report.append("╠══════════════════════════════════════════════════╣\n");

        // Desglose por tipo MIME
        report.append("║ BY MIME TYPE:                                    ║\n");
        synchronized (assetsByType) {
            for (Map.Entry<String, Integer> entry : assetsByType.entrySet()) {
                String type = entry.getKey();
                int count = entry.getValue();
                long totalTime = loadTimeByType.getOrDefault(type, 0L);
                long avgTime = count > 0 ? totalTime / count : 0;
                report.append(String.format("║   %-20s x%-4d avg %4dms           ║\n",
                    type, count, avgTime));
            }
        }
        report.append("╠══════════════════════════════════════════════════╣\n");

        // Últimas cargas
        report.append("║ RECENT LOADS (last 5):                           ║\n");
        synchronized (recentLoads) {
            int start = Math.max(0, recentLoads.size() - 5);
            for (int i = start; i < recentLoads.size(); i++) {
                AssetLoadRecord record = recentLoads.get(i);
                String shortPath = record.path.length() > 35 ?
                    "..." + record.path.substring(record.path.length() - 32) :
                    record.path;
                report.append(String.format("║   %s %s %dms║\n",
                    record.fromCache ? "💾" : "📄",
                    String.format("%-32s", shortPath),
                    record.loadTimeMs
                ));
            }
        }

        report.append("╚══════════════════════════════════════════════════╝\n");

        String reportStr = report.toString();
        LoggingUtil.logToFile(context, reportStr);

        return reportStr;
    }

    @Override
    public String generateSummary() {
        if (totalAssetsLoaded == 0) return "Sin datos de métricas";

        double cacheHitRate = totalAssetsLoaded > 0 ?
            (double) totalCacheHits / totalAssetsLoaded * 100 : 0;

        long avgLoadTime = totalAssetsLoaded > 0 ?
            totalLoadTimeMs / totalAssetsLoaded : 0;

        long sessionDuration = getSessionDuration();

        return String.format(
            "📊 %d assets | %.0f%% cache | %dms avg | %d errors | %dms total",
            totalAssetsLoaded,
            cacheHitRate,
            avgLoadTime,
            httpErrors,
            sessionDuration
        );
    }

    @Override
    public void reset() {
        sessionStartTime = 0;
        sessionEndTime = 0;
        totalBytesLoaded = 0;
        totalBytesFromCache = 0;
        totalCacheHits = 0;
        totalCacheMisses = 0;
        totalAssetsLoaded = 0;
        totalLoadTimeMs = 0;
        minLoadTimeMs = Long.MAX_VALUE;
        maxLoadTimeMs = 0;
        httpErrors = 0;
        spaFallbacks = 0;
        rangeRequests = 0;
        synchronized (assetsByType) {
            assetsByType.clear();
            loadTimeByType.clear();
        }
        synchronized (recentLoads) {
            recentLoads.clear();
        }
    }

    // ==================== GETTERS ====================

    @Override
    public List<AssetLoadRecord> getRecentLoads() {
        synchronized (recentLoads) {
            return new ArrayList<>(recentLoads);
        }
    }

    public long getSessionDuration() {
        if (sessionEndTime > 0) {
            return sessionEndTime - sessionStartTime;
        }
        return sessionStartTime > 0 ?
            SystemClock.elapsedRealtime() - sessionStartTime : 0;
    }

    public int getTotalAssetsLoaded() {
        return totalAssetsLoaded;
    }

    public int getTotalCacheHits() {
        return totalCacheHits;
    }

    public int getTotalCacheMisses() {
        return totalCacheMisses;
    }

    public double getCacheHitRate() {
        if (totalAssetsLoaded == 0) return 0;
        return (double) totalCacheHits / totalAssetsLoaded * 100;
    }

    public long getAverageLoadTimeMs() {
        if (totalAssetsLoaded == 0) return 0;
        return totalLoadTimeMs / totalAssetsLoaded;
    }

    public long getTotalBytesLoaded() {
        return totalBytesLoaded;
    }

    public int getHttpErrors() {
        return httpErrors;
    }

    public int getSpaFallbacks() {
        return spaFallbacks;
    }

    public int getRangeRequests() {
        return rangeRequests;
    }

    // ==================== HELPERS ====================

    private static String extractMimeType(String path) {
        if (path == null) return "unknown";
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "HTML";

        String ext = path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "html": case "htm": return "HTML";
            case "css": return "CSS";
            case "js": case "mjs": return "JavaScript";
            case "json": case "map": return "JSON";
            case "png": case "jpg": case "jpeg":
            case "gif": case "svg": case "webp":
            case "ico": case "bmp": return "Image";
            case "woff": case "woff2":
            case "ttf": case "otf": case "eot": return "Font";
            case "mp4": case "webm": return "Video";
            case "mp3": case "ogg": case "wav": return "Audio";
            case "wasm": return "WASM";
            default: return ext.toUpperCase();
        }
    }
}