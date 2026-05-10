package com.webvirt.utils;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LoggingUtil {
	private static final String LOG_FILENAME = "app_logs.txt";
	private static Uri logFileUri = null;
	
	public static void logToFile(Context context, String message) {
		String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
		String logMessage = timestamp + " - " + message + "\n";
		
		try {
			// Si no tenemos la URI del archivo, intentar encontrarlo o crearlo
			if (logFileUri == null) {
				logFileUri = findLogFile(context);
			}
			
			// Si no existe el archivo, crearlo
			if (logFileUri == null) {
				logFileUri = createLogFile(context);
			}
			
			// Escribir en el archivo (modo append)
			if (logFileUri != null) {
				try (OutputStream outputStream = context.getContentResolver().openOutputStream(logFileUri, "wa")) {
					if (outputStream != null) {
						outputStream.write(logMessage.getBytes());
						outputStream.flush();
					}
				}
			}
			} catch (Exception e) {
			e.printStackTrace();
			// Resetear la URI para forzar recreación en el próximo log
			logFileUri = null;
		}
	}
	
	private static Uri createLogFile(Context context) {
		ContentValues values = new ContentValues();
		values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, LOG_FILENAME);
		values.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
		values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
		
		try {
			Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
			
			// Escribir header inicial
			if (uri != null) {
				try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
					if (outputStream != null) {
						String header = "=== APPLICATION LOGS ===\n" +
						"Created: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n" +
						"========================\n\n";
						outputStream.write(header.getBytes());
						outputStream.flush();
					}
				}
			}
			return uri;
			} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private static Uri findLogFile(Context context) {
		try {
			// Buscar el archivo de logs por nombre
			Uri collection = MediaStore.Files.getContentUri("external");
			String[] projection = { MediaStore.Files.FileColumns._ID };
			String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
			MediaStore.Files.FileColumns.DISPLAY_NAME + " = ?";
			String[] selectionArgs = new String[] {
				"%" + Environment.DIRECTORY_DOCUMENTS + "%",
				LOG_FILENAME
			};
			
			android.database.Cursor cursor = context.getContentResolver().query(
			collection, projection, selection, selectionArgs, null);
			
			if (cursor != null && cursor.moveToFirst()) {
				long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID));
				cursor.close();
				return Uri.withAppendedPath(collection, String.valueOf(id));
			}
			if (cursor != null) {
				cursor.close();
			}
			} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	// Método para limpiar el archivo de logs (opcional)
	public static void clearLogs(Context context) {
		try {
			if (logFileUri != null) {
				// Eliminar el archivo existente
				context.getContentResolver().delete(logFileUri, null, null);
				logFileUri = null;
				
				// Crear uno nuevo vacío
				logFileUri = createLogFile(context);
			}
			} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	// Método para obtener la URI del archivo de logs (útil para compartir o visualizar)
	public static Uri getLogFileUri(Context context) {
		if (logFileUri == null) {
			logFileUri = findLogFile(context);
		}
		return logFileUri;
	}
}