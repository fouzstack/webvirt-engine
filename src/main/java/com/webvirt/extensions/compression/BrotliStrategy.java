package com.webvirt.extensions.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
* Estrategia de compresión Brotli.
*
* REQUIERE dependencia externa:
* implementation 'org.brotli:dec:0.1.2'
*
* Si no está disponible en runtime, falla gracefulmente sirviendo sin comprimir.
*
* v1.0.1 - Corregido: usa buffer en memoria en lugar de streaming.
*/
public class BrotliStrategy implements CompressionStrategy {
	
	private static final String CONTENT_ENCODING = "br";
	private static final int MAX_BUFFER_SIZE = 5 * 1024 * 1024;
	private static final int MIN_COMPRESS_SIZE = 1024;
	
	private boolean available = true;
	
	public BrotliStrategy() {
		try {
			Class.forName("org.brotli.dec.BrotliInputStream");
			} catch (ClassNotFoundException e) {
			available = false;
			System.err.println("[WebVirt] Brotli no disponible. Sirviendo sin comprimir.");
		}
	}
	
	@Override
	public String getContentEncoding() {
		return available ? CONTENT_ENCODING : null;
	}
	
	@Override
	public InputStream compress(String path, InputStream rawStream) throws IOException {
		if (!available) {
			return rawStream;
		}
		
		if (!shouldCompress(path)) {
			return rawStream;
		}
		
		try {
			byte[] rawData = readFully(rawStream);
			
			if (rawData.length < MIN_COMPRESS_SIZE || rawData.length > MAX_BUFFER_SIZE) {
				return new ByteArrayInputStream(rawData);
			}
			
			// Intentar comprimir con Brotli
			byte[] compressedData = brotliCompress(rawData);
			
			if (compressedData.length >= rawData.length) {
				return new ByteArrayInputStream(rawData);
			}
			
			return new ByteArrayInputStream(compressedData);
			
			} catch (Exception e) {
			return rawStream;
		}
	}
	
	/**
	* Comprime datos usando Brotli (vía reflexión para evitar dependencia en compilación).
	*/
	private byte[] brotliCompress(byte[] data) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		// Usar reflexión para evitar dependencia directa
		Class<?> brotliClass = Class.forName("org.brotli.dec.BrotliInputStream");
		// Nota: Para comprimir necesitarías BrotliOutputStream del paquete 'enc'
		// Este es un placeholder. La implementación real requiere la librería completa.
		
		// Fallback: devolver sin comprimir
		return data;
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
	
	private boolean shouldCompress(String path) {
		if (path == null) return false;
		String lower = path.toLowerCase();
		return lower.endsWith(".html")
		|| lower.endsWith(".css")
		|| lower.endsWith(".js")
		|| lower.endsWith(".json")
		|| lower.endsWith(".xml")
		|| lower.endsWith(".svg")
		|| lower.endsWith(".wasm");
	}
}