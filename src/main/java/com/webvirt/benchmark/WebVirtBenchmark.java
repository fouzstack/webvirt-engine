package com.webvirt.benchmark;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.webvirt.WebVirt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* WebVirtBenchmark v3.1 — Corregido
* Benchmark ligero optimizado para producción
*/
public class WebVirtBenchmark {
	
	private static final int MAX_DURATION_MS = 5000;
	private static final int SINGLE_TIMEOUT_MS = 3000;
	
	private final Context appContext;
	private final WebVirt webVirt;
	private final String baseUrl;
	
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Object lock = new Object();
	
	private volatile long pageFinishedTime = 0;
	private volatile boolean waiting = false;
	
	private WebView benchmarkView;
	private Thread benchmarkThread;
	private BenchmarkListener listener;
	
	public interface BenchmarkListener {
		void onBenchmarkCompleted(long coldMs, long warmMs, long memoryKb);
		void onBenchmarkError(String error);
	}
	
	public WebVirtBenchmark(@NonNull Context context, @NonNull WebVirt webVirt, @NonNull String baseUrl) {
		this.appContext = context.getApplicationContext();
		this.webVirt = webVirt;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
	}
	
	public void setBenchmarkListener(BenchmarkListener listener) {
		this.listener = listener;
	}
	
	/**
	* Benchmark ligero: solo 2 mediciones (cold/warm)
	* Duración máxima: 5 segundos
	*/
	public void runQuickBenchmark() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		
		benchmarkThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					long benchmarkStart = SystemClock.elapsedRealtime();
					
					// Crear WebView
					createBenchmarkView();
					if (benchmarkView == null) {
						notifyError("No se pudo crear WebView");
						return;
					}
					
					Thread.sleep(500);
					
					// Verificar timeout
					if (SystemClock.elapsedRealtime() - benchmarkStart > MAX_DURATION_MS) {
						notifyError("Timeout excedido");
						return;
					}
					
					// 1. Cold start (limpiar cache)
					webVirt.clearCache();
					Thread.sleep(200);
					long coldStartTime = SystemClock.elapsedRealtime();
					long coldDuration = measureLoad();
					long coldMs = (coldDuration > 0) ?
					(coldDuration - coldStartTime) : -1;
					
					// Verificar timeout
					if (SystemClock.elapsedRealtime() - benchmarkStart > MAX_DURATION_MS) {
						notifyError("Timeout excedido");
						return;
					}
					
					// 2. Warm start (con cache)
					Thread.sleep(300);
					long warmStartTime = SystemClock.elapsedRealtime();
					long warmDuration = measureLoad();
					long warmMs = (warmDuration > 0) ?
					(warmDuration - warmStartTime) : -1;
					
					// 3. Memoria
					Runtime runtime = Runtime.getRuntime();
					long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024;
					
					// Validar resultados
					if (coldMs <= 0 || warmMs <= 0) {
						notifyError("Mediciones inválidas: cold=" + coldMs + " warm=" + warmMs);
						return;
					}
					
					// Filtrar valores anómalos (> 30 segundos = error)
					if (coldMs > 30000 || warmMs > 30000) {
						notifyError("Valores anómalos detectados");
						return;
					}
					
					// Notificar resultados
					if (listener != null) {
						final long finalColdMs = coldMs;
						final long finalWarmMs = warmMs;
						final long finalMemoryKb = usedMemory;
						
						new Handler(Looper.getMainLooper()).post(new Runnable() {
							@Override
							public void run() {
								listener.onBenchmarkCompleted(
								finalColdMs,
								finalWarmMs,
								finalMemoryKb
								);
							}
						});
					}
					
					// Guardar CSV ligero
					saveQuickResults(coldMs, warmMs, usedMemory);
					
					} catch (Exception e) {
					notifyError(e.getMessage());
					} finally {
					destroyBenchmarkView();
					running.set(false);
				}
			}
		}, "Benchmark-Quick");
		
		benchmarkThread.start();
	}
	
	private void createBenchmarkView() {
		final Object viewLock = new Object();
		final WebView[] viewHolder = new WebView[1];
		
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					WebView view = new WebView(appContext);
					WebSettings settings = view.getSettings();
					settings.setJavaScriptEnabled(true);
					settings.setDomStorageEnabled(true);
					
					view.setWebViewClient(new WebViewClient() {
						@Override
						public void onPageFinished(WebView view, String url) {
							synchronized (lock) {
								if (waiting) {
									pageFinishedTime = SystemClock.elapsedRealtime();
									lock.notifyAll();
								}
							}
						}
					});
					
					viewHolder[0] = view;
					} catch (Exception e) {
					// Ignorar
				}
				
				synchronized (viewLock) {
					viewLock.notifyAll();
				}
			}
		});
		
		synchronized (viewLock) {
			try {
				viewLock.wait(3000);
				} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		benchmarkView = viewHolder[0];
	}
	
	/**
	* CORREGIDO: Ahora mide el tiempo que tarda en cargar
	* @return timestamp cuando terminó de cargar
	*/
	private long measureLoad() throws InterruptedException {
		if (benchmarkView == null) return -1;
		
		synchronized (lock) {
			waiting = true;
			pageFinishedTime = 0;
			
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					if (benchmarkView != null) {
						benchmarkView.loadUrl(baseUrl);
					}
				}
			});
			
			long timeoutEnd = SystemClock.elapsedRealtime() + SINGLE_TIMEOUT_MS;
			
			while (waiting && pageFinishedTime == 0) {
				long remaining = timeoutEnd - SystemClock.elapsedRealtime();
				if (remaining <= 0) break;
				
				lock.wait(Math.min(remaining, 1000));
			}
			
			waiting = false;
			return pageFinishedTime;
		}
	}
	
	private void destroyBenchmarkView() {
		if (benchmarkView != null) {
			final WebView view = benchmarkView;
			benchmarkView = null;
			
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					try {
						view.stopLoading();
						view.destroy();
						} catch (Exception e) {
						// Ignorar
					}
				}
			});
		}
	}
	
	private void saveQuickResults(long coldMs, long warmMs, long memoryKb) {
		try {
			File dir = new File(appContext.getExternalFilesDir(null), "WebVirtBenchmarks");
			if (!dir.exists()) dir.mkdirs();
			
			File csvFile = new File(dir, "webvirt_benchmark_quick.csv");
			FileWriter fw = null;
			
			try {
				fw = new FileWriter(csvFile);
				fw.write("metric,value\n");
				fw.write("coldStart_ms," + coldMs + "\n");
				fw.write("warmStart_ms," + warmMs + "\n");
				fw.write("memory_kb," + memoryKb + "\n");
				fw.write("timestamp," + System.currentTimeMillis() + "\n");
				} finally {
				if (fw != null) {
					try { fw.close(); } catch (IOException e) {}
				}
			}
			} catch (IOException e) {
			// Ignorar en producción
		}
	}
	
	private void notifyError(String error) {
		if (listener != null) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					listener.onBenchmarkError(error);
				}
			});
		}
	}
	
	public void stop() {
		running.set(false);
		synchronized (lock) {
			lock.notifyAll();
		}
		if (benchmarkThread != null) {
			benchmarkThread.interrupt();
		}
		destroyBenchmarkView();
	}
	
	public boolean isRunning() {
		return running.get();
	}
}