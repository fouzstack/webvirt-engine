package com.webvirt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.webvirt.utils.LoggingUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WebVirtMetrics implements WebVirtMetricsCollector {
	
	private static final String TAG = "WebVirtMetrics";
	private static final int MAX_RECENT_LOADS = 100;
	
	// Session tracking
	private long sessionStartTime;
	private long sessionEndTime = 0;
	private boolean sessionEnded = false;
	private boolean sessionStarted = false;
	
	// Asset counters
	private final AtomicInteger totalAssetsLoaded = new AtomicInteger(0);
	private final AtomicInteger totalCacheHits = new AtomicInteger(0);
	private final AtomicInteger totalCacheMisses = new AtomicInteger(0);
	private final AtomicLong totalBytesServed = new AtomicLong(0);
	
	// Timing
	private final AtomicLong totalLoadTimeMs = new AtomicLong(0);
	private long minLoadTimeMs = Long.MAX_VALUE;
	private long maxLoadTimeMs = Long.MIN_VALUE;
	private final Object timingLock = new Object();
	
	// Special counters
	private final AtomicInteger rangeRequests = new AtomicInteger(0);
	private final AtomicInteger spaFallbacks = new AtomicInteger(0);
	private final AtomicInteger httpErrors = new AtomicInteger(0);
	
	// Recent loads (thread-safe limited list)
	private final List<AssetLoadRecord> recentLoads =
	Collections.synchronizedList(new ArrayList<>(MAX_RECENT_LOADS));
	
	private static final SimpleDateFormat TIMESTAMP_FORMAT =
	new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
	
	public WebVirtMetrics() {
		// Session will start when startSession() is called
		LoggingUtil.logToFileStatic("[WebVirtMetrics] Instance created");
	}
	
	// ==================== SESSION MANAGEMENT ====================
	
	@Override
	public void startSession() {
		if (sessionStarted) {
			LoggingUtil.logToFileStatic("[WebVirtMetrics] WARN: Session already started");
			return;
		}
		
		sessionStarted = true;
		sessionEnded = false;
		sessionStartTime = System.currentTimeMillis();
		
		LoggingUtil.logToFileStatic(
		"[WebVirtMetrics] Session started at " + formatTimestamp(sessionStartTime)
		);
	}
	
	@Override
	public void endSession() {
		if (!sessionStarted) {
			LoggingUtil.logToFileStatic("[WebVirtMetrics] WARN: Session not started yet");
			return;
		}
		
		if (sessionEnded) {
			LoggingUtil.logToFileStatic("[WebVirtMetrics] WARN: Session already ended");
			return;
		}
		
		sessionEnded = true;
		sessionEndTime = System.currentTimeMillis();
		
		LoggingUtil.logToFileStatic(
		"[WebVirtMetrics] Session ended at " + formatTimestamp(sessionEndTime)
		);
		LoggingUtil.logToFileStatic(
		"[WebVirtMetrics] Session duration: " + getSessionDurationFormatted()
		);
	}
	
	// ==================== RECORDING METHODS ====================
	
	@Override
	public void recordAssetLoad(String path, long loadTimeMs, boolean fromCache, long fileSize) {
		int count = totalAssetsLoaded.incrementAndGet();
		
		LoggingUtil.logToFileStatic(
		String.format("[WebVirtMetrics] ASSET #%d: path=%s | time=%dms | cache=%s | size=%d",
		count, path, loadTimeMs, fromCache, fileSize)
		);
		
		if (fromCache) {
			totalCacheHits.incrementAndGet();
			} else {
			totalCacheMisses.incrementAndGet();
		}
		
		totalBytesServed.addAndGet(fileSize);
		totalLoadTimeMs.addAndGet(loadTimeMs);
		
		synchronized (timingLock) {
			if (loadTimeMs < minLoadTimeMs) minLoadTimeMs = loadTimeMs;
			if (loadTimeMs > maxLoadTimeMs) maxLoadTimeMs = loadTimeMs;
		}
		
		// Add to recent loads
		if (recentLoads.size() < MAX_RECENT_LOADS) {
			recentLoads.add(new AssetLoadRecord(path, null, loadTimeMs, fromCache, fileSize));
		}
	}
	
	@Override
	public void recordHttpError() {
		int count = httpErrors.incrementAndGet();
		LoggingUtil.logToFileStatic("[WebVirtMetrics] HTTP_ERROR #" + count);
	}
	
	@Override
	public void recordSpaFallback() {
		int count = spaFallbacks.incrementAndGet();
		LoggingUtil.logToFileStatic("[WebVirtMetrics] SPA_FALLBACK #" + count);
	}
	
	@Override
	public void recordRangeRequest() {
		int count = rangeRequests.incrementAndGet();
		LoggingUtil.logToFileStatic("[WebVirtMetrics] RANGE_REQUEST #" + count);
	}
	
	// ==================== REPORT GENERATION ====================
	
	@Override
	public String generateReport(@NonNull Context context) {
		if (context == null) {
			String error = "Context is null, cannot generate report";
			LoggingUtil.logToFileStatic("[WebVirtMetrics] ERROR: " + error);
			return error;
		}
		
		LoggingUtil.logToFileStatic("[WebVirtMetrics] Generating report...");
		
		try {
			int totalAssets = totalAssetsLoaded.get();
			LoggingUtil.logToFileStatic(
			"[WebVirtMetrics] Total assets to report: " + totalAssets
			);
			
			if (totalAssets == 0) {
				String msg = "No assets loaded yet. Start a session and load some content.";
				LoggingUtil.logToFileStatic("[WebVirtMetrics] " + msg);
				return msg;
			}
			
			String report = buildReportString();
			
			// Write to file
			File documentsDir = context.getExternalFilesDir(null);
			if (documentsDir == null) {
				documentsDir = context.getFilesDir();
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] Using internal storage: " + documentsDir.getAbsolutePath()
				);
				} else {
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] Using external storage: " + documentsDir.getAbsolutePath()
				);
			}
			
			File reportFile = new File(documentsDir, "webvirt_metrics.txt");
			LoggingUtil.logToFileStatic(
			"[WebVirtMetrics] Writing report to: " + reportFile.getAbsolutePath()
			);
			
			// Create directory if needed
			File parentDir = reportFile.getParentFile();
			if (parentDir != null && !parentDir.exists()) {
				boolean created = parentDir.mkdirs();
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] Creating directory: " + parentDir.getAbsolutePath() +
				" | success=" + created
				);
			}
			
			// Write report (overwrite mode for clean metrics report)
			try (FileWriter fw = new FileWriter(reportFile, false);
			PrintWriter pw = new PrintWriter(fw)) {
				pw.print(report);
				pw.flush();
				
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] Report written successfully | size=" + report.length() + " chars"
				);
			}
			
			// Verify file was written
			if (reportFile.exists()) {
				long fileSize = reportFile.length();
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] VERIFIED: File exists | path=" + reportFile.getAbsolutePath() +
				" | size=" + fileSize + " bytes"
				);
				} else {
				LoggingUtil.logToFileStatic(
				"[WebVirtMetrics] ERROR: File does not exist after write attempt!"
				);
			}
			
			return report;
			
			} catch (IOException e) {
			String error = "IOException generating report: " + e.getMessage();
			LoggingUtil.logToFileStatic("[WebVirtMetrics] ERROR: " + error);
			LoggingUtil.logToFileStatic("[WebVirtMetrics] Stack trace: " +
			Log.getStackTraceString(e));
			return error;
			} catch (Exception e) {
			String error = "Unexpected error generating report: " + e.getMessage();
			LoggingUtil.logToFileStatic("[WebVirtMetrics] ERROR: " + error);
			LoggingUtil.logToFileStatic("[WebVirtMetrics] Stack trace: " +
			Log.getStackTraceString(e));
			return error;
		}
	}
	
	@Override
	public String generateSummary() {
		int total = totalAssetsLoaded.get();
		if (total == 0) {
			return "No assets loaded yet";
		}
		
		return String.format(
		"Assets: %d | Cache: %d (%.0f%%) | Time: %s | Size: %s",
		total,
		totalCacheHits.get(),
		getCacheHitRate(),
		getSessionDurationFormatted(),
		formatBytes(totalBytesServed.get())
		);
	}
	
	@Override
	public void reset() {
		LoggingUtil.logToFileStatic("[WebVirtMetrics] Resetting all metrics");
		
		totalAssetsLoaded.set(0);
		totalCacheHits.set(0);
		totalCacheMisses.set(0);
		totalBytesServed.set(0);
		totalLoadTimeMs.set(0);
		
		synchronized (timingLock) {
			minLoadTimeMs = Long.MAX_VALUE;
			maxLoadTimeMs = Long.MIN_VALUE;
		}
		
		rangeRequests.set(0);
		spaFallbacks.set(0);
		httpErrors.set(0);
		
		recentLoads.clear();
		
		sessionStartTime = 0;
		sessionEndTime = 0;
		sessionEnded = false;
		sessionStarted = false;
		
		LoggingUtil.logToFileStatic("[WebVirtMetrics] All metrics reset");
	}
	
	@Override
	public List<AssetLoadRecord> getRecentLoads() {
		synchronized (recentLoads) {
			return new ArrayList<>(recentLoads);
		}
	}
	
	// ==================== GETTERS ====================
	
	public int getTotalAssetsLoaded() {
		return totalAssetsLoaded.get();
	}
	
	public int getCacheHits() {
		return totalCacheHits.get();
	}
	
	public int getCacheMisses() {
		return totalCacheMisses.get();
	}
	
	public double getCacheHitRate() {
		int total = totalAssetsLoaded.get();
		return total > 0 ? (totalCacheHits.get() * 100.0) / total : 0.0;
	}
	
	public long getTotalBytesServed() {
		return totalBytesServed.get();
	}
	
	public long getAverageLoadTimeMs() {
		int total = totalAssetsLoaded.get();
		return total > 0 ? totalLoadTimeMs.get() / total : 0;
	}
	
	public long getSessionDurationMs() {
		long end = sessionEndTime > 0 ? sessionEndTime : System.currentTimeMillis();
		long start = sessionStartTime > 0 ? sessionStartTime : System.currentTimeMillis();
		return end - start;
	}
	
	public boolean isSessionEnded() {
		return sessionEnded;
	}
	
	public boolean isSessionStarted() {
		return sessionStarted;
	}
	
	public int getRangeRequests() {
		return rangeRequests.get();
	}
	
	public int getSpaFallbacks() {
		return spaFallbacks.get();
	}
	
	public int getHttpErrors() {
		return httpErrors.get();
	}
	
	// ==================== PRIVATE HELPERS ====================
	
	private String buildReportString() {
		StringBuilder sb = new StringBuilder();
		
		sb.append("╔══════════════════════════════════════════════════════╗\n");
		sb.append("║         WEBVIRT RUNTIME METRICS REPORT              ║\n");
		sb.append("╠══════════════════════════════════════════════════════╣\n");
		sb.append("\n");
		
		// Session info
		sb.append("📅 SESSION INFO\n");
		sb.append("─────────────────────────────────────────────────\n");
		if (sessionStartTime > 0) {
			sb.append("  Started:     ").append(formatTimestamp(sessionStartTime)).append("\n");
		}
		if (sessionEndTime > 0) {
			sb.append("  Ended:       ").append(formatTimestamp(sessionEndTime)).append("\n");
			sb.append("  Duration:    ").append(getSessionDurationFormatted()).append("\n");
		}
		sb.append("\n");
		
		// Asset loading stats
		sb.append("📦 ASSET LOADING\n");
		sb.append("─────────────────────────────────────────────────\n");
		sb.append(String.format("  Total:       %d\n", totalAssetsLoaded.get()));
		sb.append(String.format("  From cache:  %d (%.1f%%)\n",
		totalCacheHits.get(), getCacheHitRate()));
		sb.append(String.format("  From disk:   %d (%.1f%%)\n",
		totalCacheMisses.get(), 100.0 - getCacheHitRate()));
		sb.append("\n");
		
		// Data transfer
		sb.append("📊 DATA TRANSFER\n");
		sb.append("─────────────────────────────────────────────────\n");
		sb.append(String.format("  Total:       %s\n", formatBytes(totalBytesServed.get())));
		if (totalAssetsLoaded.get() > 0) {
			sb.append(String.format("  Avg/asset:   %s\n",
			formatBytes(totalBytesServed.get() / totalAssetsLoaded.get())));
		}
		sb.append("\n");
		
		// Performance
		sb.append("⚡ PERFORMANCE\n");
		sb.append("─────────────────────────────────────────────────\n");
		if (totalAssetsLoaded.get() > 0) {
			sb.append(String.format("  Avg time:    %d ms\n",
			totalLoadTimeMs.get() / totalAssetsLoaded.get()));
			synchronized (timingLock) {
				if (minLoadTimeMs != Long.MAX_VALUE) {
					sb.append(String.format("  Min time:    %d ms\n", minLoadTimeMs));
				}
				if (maxLoadTimeMs != Long.MIN_VALUE) {
					sb.append(String.format("  Max time:    %d ms\n", maxLoadTimeMs));
				}
			}
			sb.append(String.format("  Total time:  %d ms\n", totalLoadTimeMs.get()));
		}
		sb.append("\n");
		
		// Special requests
		sb.append("🔧 SPECIAL REQUESTS\n");
		sb.append("─────────────────────────────────────────────────\n");
		sb.append(String.format("  Range:       %d\n", rangeRequests.get()));
		sb.append(String.format("  SPA fallback: %d\n", spaFallbacks.get()));
		sb.append(String.format("  HTTP errors:  %d\n", httpErrors.get()));
		sb.append("\n");
		
		// Footer
		sb.append("╚══════════════════════════════════════════════════════╝\n");
		sb.append("Generated: ").append(formatTimestamp(System.currentTimeMillis())).append("\n");
		sb.append("WebVirt v3.5.1 — Hybrid Web Runtime Engine\n");
		
		return sb.toString();
	}
	
	private String formatTimestamp(long timestamp) {
		TIMESTAMP_FORMAT.setTimeZone(TimeZone.getDefault());
		return TIMESTAMP_FORMAT.format(new Date(timestamp));
	}
	
	private String getSessionDurationFormatted() {
		long ms = getSessionDurationMs();
		if (ms < 1000) return ms + " ms";
		if (ms < 60000) return String.format("%.1f sec", ms / 1000.0);
		long minutes = ms / 60000;
		long seconds = (ms % 60000) / 1000;
		return String.format("%d min %d sec", minutes, seconds);
	}
	
	private String formatBytes(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
		if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
		return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
	}
}