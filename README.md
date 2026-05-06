
🚀 WebVirt Engine

Serve your React/Vue/Svelte SPA from Android assets like a real web server.

⚡ 77ms first load
⚡ 2ms reload (LRU cache)
📦 ~600 lines, zero dependencies
🔒 Built-in security headers
🧠 Works with Nexus (optional native bridge)

⚡ Quick Start

WebVirt.with(this) .host("app.local") .bind(webView); webView.loadUrl("https://app.local/"); 

That’s it. Your SPA runs as if it were hosted on a real HTTPS server.

📦 Installation

repositories { maven { url 'https://jitpack.io' } } dependencies { implementation 'com.github.fouzstack:webvirt-engine:3.1.1' } 

🤔 Why?

Running SPAs inside Android WebView is painful:

❌ file:// breaks routing and CORS

❌ Capacitor / Cordova are heavy

❌ Custom scripts are fragile and slow

WebVirt solves this by simulating a local HTTPS server inside WebView.

Your app thinks it's running at:

https://app.local/ 

But everything is served from assets/.

📊 Performance

MetricFirst LoadCached ReloadTotal load time77ms2msCache hit rate0%100%Bytes from cache01.4MBHTTP errors00 

✨ Features

⚡ In-memory LRU cache

🔐 Automatic security headers (CSP, XSS, etc.)

🌐 Full SPA routing support (React Router, etc.)

📦 Asset loading from APK

🧠 ETag-based caching (304 Not Modified)

🪶 Lightweight (~600 LOC)

🔌 Zero dependencies

🔒 Security

Every response includes:

Content-Security-Policy X-Content-Type-Options X-Frame-Options X-XSS-Protection Access-Control-Allow-Origin 

Custom CSP:

WebVirt.with(this) .host("app.local") .cspPolicy("default-src 'self'; script-src 'self' https://api.example.com") .bind(webView); 

🤝 Works with Nexus (Optional)

Need native capabilities?

Use Nexus as a bridge between JavaScript and Android:

WebVirt.with(this) .host("app.local") .bind(webView); Nexus.installOn(webView) .registerHandler("export", new ExportAdapter()) .registerHandler("import", new ImportAdapter()) .initialize(); webView.loadUrl("https://app.local/"); 

✔ No coupling
✔ Independent lifecycles
✔ Clean architecture

🏗️ Architecture

WebView ├── WebViewClient → WebVirt │ └── Intercepts requests → assets/ │ ├── Lifecycle Observer → Nexus (optional) │ └── JS Bridge → Nexus 

WebVirt → serves the SPA

Nexus → handles native communication

Fully decoupled by design

🧪 Real-World Metrics (Detailed)

First Load (Disk)

Total assets: 3 Total load time: 77ms Avg: 25ms Max: 49ms Cache hit rate: 0% 

Cached Reload (RAM)

Total assets: 3 Total load time: 2ms Cache hit rate: 100% All bytes served from memory 

🧠 Why It’s Fast

No network stack

No disk I/O on reload

In-memory LRU cache

ETag validation (304)

Assets read directly from APK

🚀 Use Cases

✅ Perfect if you:

Use React / Vue / Svelte

Want offline-first apps

Need maximum performance

Care about architecture

❌ Not ideal if you:

Need hot reload in WebView

Already depend on Capacitor/Cordova

Are building a purely native UI

📦 Roadmap

[ ] Dev mode support (hot reload)

[ ] Advanced cache strategies

[ ] Streaming assets

[ ] Better debugging tools

💬 Contributing

PRs and issues are welcome once the repo is public.

📣 Deep Dive (Original Story)

Click to expand full performance story 

🚀 I Served My React SPA from Android Assets Like a Professional Web Server

First load: 77ms. Reload: 2ms. 38x faster with LRU cache.

The Problem

Your SPA works perfectly on:

http://localhost:5173 

But in Android:

file:// breaks routing

CORS issues

Heavy frameworks required

The Result

3 assets loaded in 77ms

Reload in 2ms

100% cache hit rate

Metrics Snapshot

/index.html → 10ms /index.css → 18ms /index.js → 49ms 

Reload:

All assets → 0–1ms (RAM) 

How It Works

First load: APK → RAM cache → ETag Reload: ETag match → 304 → instant 

Architecture Insight

WebVirt = Runtime Web

Nexus = Runtime Native

Zero coupling

⭐ Final Thought

This is not a wrapper.
This is not a hack.

This is a virtual web server inside your WebView.

If this helped you, ⭐ the repo.

