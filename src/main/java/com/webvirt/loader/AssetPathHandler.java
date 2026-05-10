package com.webvirt.loader;

import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* Handler optimizado para archivos en assets/
*
* v3.2.1 - Forzar ByteArrayInputStream para permitir cacheo completo
*
* Cambios v3.2.1:
* - Eliminado isLargeFile() que impedía cachear JS/CSS grandes
* - Siempre devuelve ByteArrayInputStream (cacheable por WebVirtFileLoader)
* - Solo hace streaming si hay OutOfMemoryError (archivos >10MB aproximadamente)
* - Range requests mantienen streaming con RangeInputStream
*/
class AssetPathHandler implements PathHandler {
	
	private static final String TAG = "AssetPathHandler";
	private final AssetManager assetManager;
	private final String basePath;
	private static final int BUFFER_SIZE = 8192;
	
	AssetPathHandler(@NonNull AssetManager assetManager, @NonNull String basePath) {
		this.assetManager = assetManager;
		String normalized = basePath.trim();
		if (!normalized.isEmpty() && !normalized.endsWith("/")) {
			normalized += "/";
		}
		this.basePath = normalized;
	}
	
	/**
	* Maneja una petición de archivo desde assets.
	*
	* v3.2.1: Siempre devuelve ByteArrayInputStream para que
	* WebVirtFileLoader pueda cachear el asset completo.
	* Solo hace streaming si hay OutOfMemoryError.
	*/
	@Nullable
	@Override
	public WebResourceResponse handle(
	@NonNull String path,
	@NonNull WebResourceRequest request
	) throws IOException {
		String cleanPath = path.startsWith("/") ? path.substring(1) : path;
		String fullPath = basePath + cleanPath;
		
		// Abrir archivo desde assets
		InputStream inputStream;
		try {
			inputStream = assetManager.open(fullPath);
			} catch (FileNotFoundException e) {
			return null;
		}
		
		// Verificar si es un directorio (servir index.html)
		try {
			String[] list = assetManager.list(fullPath);
			if (list != null && list.length > 0 && !fullPath.endsWith(".html")) {
				inputStream.close();
				String indexPath = fullPath.endsWith("/")
				? fullPath + "index.html"
				: fullPath + "/index.html";
				try {
					inputStream = assetManager.open(indexPath);
					fullPath = indexPath;
					} catch (IOException e) {
					return null;
				}
			}
			} catch (IOException e) {
			// No es directorio, continuar con el archivo original
		}
		
		String mimeType = MimeTypeResolver.getMimeType(fullPath);
		
		// v3.2.1: Intentar siempre leer completamente para permitir cacheo
		// Solo hacer streaming si hay OutOfMemoryError (archivos extremadamente grandes)
		try {
			byte[] data = readFully(inputStream);
			
			if (data.length > 0) {
				Log.d(TAG, "📄 Servido completo (cacheable): " + fullPath +
				" (" + data.length + " bytes, " + mimeType + ")");
			}
			
			return new WebResourceResponse(
			mimeType,
			"UTF-8",
			new ByteArrayInputStream(data)
			);
			} catch (OutOfMemoryError oom) {
			// Si el archivo es demasiado grande para memoria, servir como streaming
			Log.w(TAG, "⚠️ OOM leyendo " + fullPath + " (" +
			getFileSize(fullPath) + " bytes estimados), sirviendo como streaming");
			
			// Cerrar el stream original y abrir uno nuevo
			try {
				inputStream.close();
			} catch (IOException ignored) {}
			
			try {
				InputStream freshStream = assetManager.open(fullPath);
				return new WebResourceResponse(
				mimeType,
				"UTF-8",
				freshStream
				);
				} catch (IOException e) {
				Log.e(TAG, "❌ Error re-abriendo " + fullPath + ": " + e.getMessage());
				return null;
			}
		}
	}
	
	/**
	* Maneja peticiones Range para streaming parcial.
	* Mantiene streaming con RangeInputStream para video/audio.
	*/
	@Nullable
	public WebResourceResponse handleRange(
	@NonNull String path,
	@NonNull String rangeHeader,
	@NonNull WebResourceRequest request) {
		
		String cleanPath = path.startsWith("/") ? path.substring(1) : path;
		String fullPath = basePath + cleanPath;
		
		try {
			InputStream is = assetManager.open(fullPath);
			long fileSize = is.available();
			is.close();
			
			if (fileSize <= 0) return null;
			
			// Usar RangeParser centralizado
			long[] range = RangeParser.parse(rangeHeader, fileSize);
			if (range == null) {
				return create416Response(fileSize);
			}
			
			long start = range[0];
			long end = range[1];
			int contentLength = (int) (end - start + 1);
			
			InputStream stream = assetManager.open(fullPath);
			long skipped = 0;
			while (skipped < start) {
				long skipResult = stream.skip(start - skipped);
				if (skipResult <= 0) {
					stream.close();
					throw new IOException("Could not skip to range start");
				}
				skipped += skipResult;
			}
			
			// Usar RangeInputStream externo para streaming controlado
			InputStream partialStream = new BufferedInputStream(
			new RangeInputStream(stream, contentLength), BUFFER_SIZE
			);
			
			String mimeType = MimeTypeResolver.getMimeType(fullPath);
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
			headers.put("Content-Length", String.valueOf(contentLength));
			headers.put("Accept-Ranges", "bytes");
			headers.put("Content-Type", mimeType);
			headers.put("Cache-Control", "no-cache");
			
			return new WebResourceResponse(
			mimeType,
			"UTF-8",
			206,
			"Partial Content",
			headers,
			partialStream
			);
			
			} catch (IOException e) {
			Log.e(TAG, "❌ Error en Range request: " + e.getMessage());
			return null;
		}
	}
	
	@NonNull
	private WebResourceResponse create416Response(long fileSize) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Range", "bytes */" + fileSize);
		headers.put("Content-Length", "0");
		
		return new WebResourceResponse(
		"text/plain",
		"UTF-8",
		416,
		"Range Not Satisfiable",
		headers,
		new ByteArrayInputStream(new byte[0])
		);
	}
	
	/**
	* Lee completamente un InputStream a un byte[].
	*/
	private byte[] readFully(InputStream is) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[BUFFER_SIZE];
		int bytesRead;
		while ((bytesRead = is.read(buffer)) != -1) {
			baos.write(buffer, 0, bytesRead);
		}
		return baos.toByteArray();
	}
	
	/**
	* Estima el tamaño de un archivo en assets sin leerlo completamente.
	*/
	private long getFileSize(String fullPath) {
		try {
			InputStream is = assetManager.open(fullPath);
			long size = is.available();
			is.close();
			return size;
			} catch (IOException e) {
			return -1;
		}
	}
	
	/**
	* Obtiene la extensión de un archivo.
	*/
	private String getExtension(String path) {
		int lastDot = path.lastIndexOf('.');
		return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : null;
	}
}