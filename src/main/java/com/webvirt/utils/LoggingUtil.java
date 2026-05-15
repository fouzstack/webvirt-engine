package com.webvirt.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
* LoggingUtil v3.5.3 — Auto-inicializable, sin permisos, Android 11+.
*
* NO requiere permisos. Usa almacenamiento externo privado de la app.
* Compatible con logToFileStatic() sin init() previo gracias a
* inicialización lazy desde WebVirt.
*/
public class LoggingUtil {
	
	private static final String TAG = "LoggingUtil";
	private static final String LOG_FILENAME = "webvirt_metrics.txt";
	
	// Contexto estático para logToFileStatic()
	private static Context appContext;
	
	/**
	* Inicialización explícita (opcional pero recomendada).
	* Si no se llama, logToFileStatic() usará inicialización lazy.
	*/
	public static void init(Context context) {
		if (context != null) {
			appContext = context.getApplicationContext();
			Log.d(TAG, "✅ Inicializado: " + appContext.getPackageName());
		}
	}
	
	/**
	* Escribe un mensaje al archivo de log.
	* Auto-inicializa si no se llamó a init() antes.
	* Usado por WebVirtMetrics.
	*/
	public static synchronized void logToFileStatic(String message) {
		// Siempre loguear a Logcat
		Log.d(TAG, message);
		
		// Si no hay contexto, no podemos escribir a archivo
		if (appContext == null) {
			Log.w(TAG, "⚠️ Contexto no disponible para: " + message);
			return;
		}
		
		writeToFile(appContext, message);
	}
	
	/**
	* Escribe un mensaje al archivo de log usando el contexto proporcionado.
	* Usado por WebVirtFileLoader.
	*/
	public static synchronized void logToFile(Context context, String message) {
		// Siempre loguear a Logcat
		Log.d(TAG, message);
		
		if (context == null) {
			Log.w(TAG, "⚠️ Contexto null para: " + message);
			return;
		}
		
		// Guardar contexto para futuros logToFileStatic()
		if (appContext == null) {
			appContext = context.getApplicationContext();
		}
		
		writeToFile(context, message);
	}
	
	/**
	* Escritura real al archivo.
	* NO requiere permisos - usa almacenamiento externo privado.
	*/
	private static void writeToFile(Context context, String message) {
		File logFile = null;
		
		try {
			// Usar almacenamiento externo privado (NO requiere permisos)
			File filesDir = context.getExternalFilesDir(null);
			
			// Fallback a almacenamiento interno si externo no disponible
			if (filesDir == null) {
				filesDir = context.getFilesDir();
				Log.d(TAG, "Usando almacenamiento interno");
			}
			
			// Crear directorio si no existe
			if (!filesDir.exists()) {
				filesDir.mkdirs();
			}
			
			logFile = new File(filesDir, LOG_FILENAME);
			
			// Formatear mensaje con timestamp
			String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
			.format(new Date());
			String logMessage = timestamp + " " + message + "\n";
			
			// Escribir en modo append
			FileWriter fw = new FileWriter(logFile, true);
			PrintWriter pw = new PrintWriter(fw);
			pw.print(logMessage);
			pw.flush();
			pw.close();
			
			} catch (IOException e) {
			Log.e(TAG, "❌ Error escribiendo log: " + e.getMessage());
			
			// Fallback a almacenamiento interno
			try {
				File fallbackDir = context.getFilesDir();
				File fallbackFile = new File(fallbackDir, LOG_FILENAME);
				FileWriter fw = new FileWriter(fallbackFile, true);
				PrintWriter pw = new PrintWriter(fw);
				pw.print(message + "\n");
				pw.flush();
				pw.close();
				Log.d(TAG, "✅ Fallback log escrito en: " + fallbackFile.getAbsolutePath());
				} catch (IOException e2) {
				Log.e(TAG, "❌ Fallback también falló: " + e2.getMessage());
			}
		}
	}
	
	/**
	* Verifica si el logging está listo para escribir.
	*/
	public static boolean isReady() {
		return appContext != null;
	}
}