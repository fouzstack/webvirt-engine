package com.webvirt.extensions.compression;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;

public class GzipStrategy implements CompressionStrategy {
	
	private static final String TAG = "GzipStrategy";
	private static final String CONTENT_ENCODING = "gzip";
	
	@Override
	public String getContentEncoding() {
		return CONTENT_ENCODING;
	}
	
	@Override
	public InputStream compress(String path, InputStream rawStream) throws IOException {
		Log.d(TAG, "📦 Iniciando compresión: " + path);
		
		try {
			// Leer stream original
			byte[] rawData = readFully(rawStream);
			Log.d(TAG, "   Raw size: " + rawData.length + " bytes");
			
			// Verificar si vale la pena comprimir
			if (!shouldCompress(path)) {
				Log.d(TAG, "   ⏭️ Tipo no compresible, sirviendo original");
				return new ByteArrayInputStream(rawData);
			}
			
			if (rawData.length < 512) {
				Log.d(TAG, "   ⏭️ Muy pequeño, sirviendo original");
				return new ByteArrayInputStream(rawData);
			}
			
			// Comprimir
			byte[] compressed = gzip(rawData);
			Log.d(TAG, "   Compressed size: " + compressed.length + " bytes");
			
			// Verificar si la compresión ayudó
			if (compressed.length >= rawData.length) {
				Log.d(TAG, "   ⏭️ Compresión no efectiva, sirviendo original");
				return new ByteArrayInputStream(rawData);
			}
			
			double ratio = (1.0 - (double)compressed.length / rawData.length) * 100;
			Log.d(TAG, "   ✅ Comprimido: " + String.format("%.1f%%", ratio) + " reducción");
			return new ByteArrayInputStream(compressed);
			
			} catch (Exception e) {
			Log.e(TAG, "❌ Error comprimiendo " + path + ": " + e.getMessage(), e);
			// Fallback: devolver stream vacío para debugging
			return new ByteArrayInputStream(new byte[0]);
		}
	}
	
	private byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
			gz.write(data);
			gz.finish();
		}
		return baos.toByteArray();
	}
	
	private byte[] readFully(InputStream is) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = is.read(buffer)) != -1) {
			baos.write(buffer, 0, n);
		}
		return baos.toByteArray();
	}
	
	private boolean shouldCompress(String path) {
		if (path == null) return false;
		String p = path.toLowerCase();
		return p.endsWith(".html") || p.endsWith(".htm")
		|| p.endsWith(".css") || p.endsWith(".js")
		|| p.endsWith(".json") || p.endsWith(".xml")
		|| p.endsWith(".svg") || p.endsWith(".txt");
	}
}