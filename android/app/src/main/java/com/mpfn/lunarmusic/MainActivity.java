package com.mpfn.lunarmusic;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lunar Music for Meta Quest: the same glassy player page as the PC and iPhone versions, in a
 * window. The page talks to this app the way it talks to the iPhone app; the app plays the music
 * (Player), and reads it from Google Drive (Drive) or from the headset (LocalLibrary).
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;

    /** Makes the page see the same message channel the iPhone app offers, and says it is the Quest. */
    private static final String SHIM = "<script>window.LUNAR_PLATFORM='quest';window.webkit={messageHandlers:{lunar:{postMessage:function(m){"
            + "try{LunarAndroid.post(JSON.stringify(m));}catch(e){}}}}};</script>";

    private WebView web;
    private HostJobs host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private ValueCallback<Uri[]> fileCallback;
    private long lastUpdateCheck = 0;
    private boolean toldAboutReinstall = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Lunar.init(this);
        getWindow().getDecorView().setBackgroundColor(Color.rgb(10, 15, 38));

        host = new HostJobs(this, text -> {
            try { send(new JSONObject().put("type", "pair").put("text", text)); } catch (Exception ignored) { }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(10, 15, 38));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "LunarAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                try {
                    return serve(u);
                } catch (Exception e) {
                    return text(500, "text/plain", String.valueOf(e.getMessage()));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                try {
                    startActivityForResult(i, 7);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);

        Lunar.player.listener = this::send;
        web.loadUrl(ORIGIN + "/");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Lunar.player.listener = this::send;
        send(Lunar.player.snapshot());
        checkForUpdate();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Lunar.player.listener != null) Lunar.player.listener = null;   // the music keeps playing
        web.destroy();
        super.onDestroy();
    }

    /** Look for a newer player page each time the app comes to the front. */
    private void checkForUpdate() {
        if (System.currentTimeMillis() - lastUpdateCheck < 15000) return;
        lastUpdateCheck = System.currentTimeMillis();
        bg.execute(() -> {
            int result = PageStore.check(this);
            main.post(() -> {
                if (result == PageStore.UPDATED) {
                    web.reload();
                } else if (result == PageStore.NEEDS_REINSTALL && !toldAboutReinstall) {
                    toldAboutReinstall = true;
                    try { send(new JSONObject().put("type", "toast").put("text", "A bigger update is out. Install the newest Quest app (APK) to get it.")); } catch (Exception ignored) { }
                }
            });
        });
    }

    /** An event for the page (window.lunarNative). */
    void send(JSONObject ev) {
        final String js = "window.lunarNative && window.lunarNative(" + ev.toString() + ");";
        main.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    // ---------- what the page loads ----------

    private static WebResourceResponse bytes(int status, String mime, byte[] data) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 404 ? "Not Found" : "Error";
        String type = mime, charset = null;
        int semi = mime.indexOf(';');
        if (semi > 0) { type = mime.substring(0, semi).trim(); charset = "utf-8"; }
        return new WebResourceResponse(type, charset, status, reason, h, new ByteArrayInputStream(data == null ? new byte[0] : data));
    }

    private static WebResourceResponse text(int status, String mime, String body) {
        return bytes(status, mime + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static WebResourceResponse json(JSONObject o) {
        return text(200, "application/json", o.toString());
    }

    private WebResourceResponse serve(Uri u) throws Exception {
        String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
        String f = u.getQueryParameter("f");
        boolean drive = Lunar.drive.isOn();
        switch (path) {
            case "/": {
                byte[] page = PageStore.current(this);
                if (page == null) return text(500, "text/plain", "app.html is missing");
                String html = new String(page, StandardCharsets.UTF_8);
                int head = html.indexOf("<head>");
                html = head >= 0 ? html.substring(0, head + 6) + SHIM + html.substring(head + 6) : SHIM + html;
                return text(200, "text/html", html);
            }
            case "/api/ping":
                return text(200, "text/plain", "ok");
            case "/api/library":
                if (drive) {
                    Lunar.drive.relist("1".equals(u.getQueryParameter("fresh")));
                    return json(Lunar.drive.library());
                }
                return json(Lunar.local.library());
            case "/api/settings":
                return json(settings());
            case "/api/lyrics": {
                String t = null;
                if (f != null) {
                    if (drive) t = Lunar.drive.lyricsFor(f);
                    else if (Lunar.local.safeFile(f) != null) t = Lunar.local.lyricsText(f);
                }
                return json(new JSONObject().put("text", t == null ? JSONObject.NULL : t));
            }
            case "/id3": {
                byte[] tag = null;
                if (f != null) {
                    if (drive) tag = Lunar.drive.id3(f);
                    else {
                        File file = Lunar.local.safeFile(f);
                        if (file != null) tag = LocalLibrary.id3Tag(file);
                    }
                }
                return tag == null ? bytes(204, "application/octet-stream", null) : bytes(200, "application/octet-stream", tag);
            }
            case "/icon": {
                byte[] data = null;
                if (f != null) {
                    if (drive) data = Lunar.drive.icon(f);
                    else {
                        File file = Lunar.local.safeFile(f);
                        if (file != null && LocalLibrary.ext(file.getName()).equals("png")) {
                            try (FileInputStream in = new FileInputStream(file)) { data = Net.readAll(in, 16L * 1024 * 1024); }
                        }
                    }
                }
                return data == null ? bytes(404, "text/plain", null) : bytes(200, "image/png", data);
            }
            case "/appicon.png": {
                File icon = customIcon();
                if (!icon.exists()) return bytes(404, "text/plain", null);
                try (FileInputStream in = new FileInputStream(icon)) { return bytes(200, "image/png", Net.readAll(in, 16L * 1024 * 1024)); }
            }
            default:
                return bytes(404, "text/plain", null);
        }
    }

    private File customIcon() { return new File(getFilesDir(), "appicon.png"); }

    private SharedPreferences prefs() { return getSharedPreferences("app", MODE_PRIVATE); }

    private JSONObject settings() throws Exception {
        Object ui = JSONObject.NULL;
        String saved = prefs().getString("ui", null);
        if (saved != null) {
            try { ui = new JSONObject(saved); } catch (Exception ignored) { }
        }
        return new JSONObject().put("songs", "").put("icons", "").put("lyrics", "").put("ui", ui)
                .put("songsFound", true).put("iconsFound", true)
                .put("customIcon", customIcon().exists()).put("iconVersion", prefs().getInt("iconVersion", 0))
                .put("caps", new JSONArray().put("sync").put("quest"));
    }

    private void bumpIcon() {
        prefs().edit().putInt("iconVersion", prefs().getInt("iconVersion", 0) + 1).apply();
        try { send(new JSONObject().put("type", "settings")); } catch (Exception ignored) { }
    }

    // ---------- messages from the page ----------

    public final class Bridge {
        @JavascriptInterface
        public void post(String raw) {
            final JSONObject m;
            try { m = new JSONObject(raw); } catch (Exception e) { return; }
            String cmd = m.optString("cmd", "");
            switch (cmd) {
                case "drive": {
                    // Handled right here (not later on the main thread) so the page's next request
                    // for the song list already sees the change.
                    boolean changed = Lunar.drive.configure(m.optBoolean("on", false), m.optString("clientId", ""),
                            m.optString("clientSecret", ""), m.optString("refresh", ""));
                    if (changed) {
                        try { send(new JSONObject().put("type", "library")); } catch (Exception ignored) { }
                    }
                    return;
                }
                case "saveUI":
                    prefs().edit().putString("ui", m.optString("ui", "")).apply();
                    return;
                case "host": {
                    final int id = m.optInt("id", -1);
                    host.handle(m, (ok, value) -> {
                        try {
                            JSONObject ev = new JSONObject().put("type", "host").put("id", id).put("ok", ok);
                            ev.put(ok ? "result" : "error", value);
                            send(ev);
                        } catch (Exception ignored) { }
                    });
                    return;
                }
                default:
                    main.post(() -> command(cmd, m));
            }
        }
    }

    private void command(String cmd, JSONObject m) {
        Player p = Lunar.player;
        switch (cmd) {
            case "hello": {
                JSONObject snap = p.snapshot();
                try { snap.put("hello", true); } catch (Exception ignored) { }
                send(snap);
                break;
            }
            case "queue": {
                List<String> files = new ArrayList<>();
                JSONArray a = m.optJSONArray("files");
                for (int i = 0; a != null && i < a.length(); i++) files.add(a.optString(i));
                p.setQueue(files, m.optString("repeat", "off"));
                break;
            }
            case "playIndex": p.load(m.optInt("pos", -1), m.optBoolean("autoplay", false)); break;
            case "play": p.play(); break;
            case "pause": p.pause(); break;
            case "next": p.next(false); break;
            case "prev": p.previous(); break;
            case "seek": p.seek(m.optDouble("t", 0)); break;
            case "volume": p.setVolume((float) m.optDouble("v", 1)); break;
            case "repeat": p.setRepeat(m.optString("mode", "off")); break;
            case "setIcon":
                bg.execute(() -> {
                    try {
                        byte[] data = Base64.decode(m.optString("data", ""), Base64.DEFAULT);
                        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (b == null) return;
                        try (FileOutputStream out = new FileOutputStream(customIcon())) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        main.post(this::bumpIcon);
                    } catch (Exception ignored) { }
                });
                break;
            case "clearIcon":
                customIcon().delete();
                bumpIcon();
                break;
            default:
                break;      // lock-screen lyrics are an iPhone feature
        }
    }
}
