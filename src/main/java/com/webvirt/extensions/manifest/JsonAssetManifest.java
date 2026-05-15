package com.webvirt.extensions.manifest;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación de AssetManifest basada en JSON.
 * 
 * Formato esperado:
 * {
 *   "assets": {
 *     "/app.js": {
 *       "hashedPath": "/app-a1b2c3.js",
 *       "integrity": "sha384-...",
 *       "size": 12345
 *     }
 *   }
 * }
 */
class JsonAssetManifest implements AssetManifest {
    
    private static final String TAG = "JsonAssetManifest";
    
    private final ConcurrentHashMap<String, AssetManifestEntry> entries = new ConcurrentHashMap<>();
    private final boolean loaded;
    
    JsonAssetManifest(Context context, String assetPath) {
        boolean success = false;
        try {
            String json = readAssetFile(context, assetPath);
            if (json != null) {
                parseJson(json);
                success = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cargando manifiesto: " + e.getMessage());
        }
        this.loaded = success;
        
        if (success) {
            Log.d(TAG, "Manifiesto cargado: " + entries.size() + " assets");
        }
    }
    
    @Nullable
    @Override
    public AssetManifestEntry resolve(String path) {
        if (!loaded) return null;
        
        // Normalizar path
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        return entries.get(path);
    }
    
    private String readAssetFile(Context context, String assetPath) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(assetPath)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error leyendo " + assetPath + ": " + e.getMessage());
            return null;
        }
    }
    
    private void parseJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject assets = root.optJSONObject("assets");
        
        if (assets == null) return;
        
        JSONObject assetObject = assets;
        java.util.Iterator<String> keys = assetObject.keys();
        
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = assetObject.getJSONObject(key);
            
            String hashedPath = entry.getString("hashedPath");
            String integrity = entry.optString("integrity", null);
            long size = entry.optLong("size", 0);
            
            entries.put(key, new AssetManifestEntry(hashedPath, integrity, size));
        }
    }
}