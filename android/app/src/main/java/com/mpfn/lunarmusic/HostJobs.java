package com.mpfn.lunarmusic;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper jobs for the page's Google Drive sign-in and sync (the page cannot reach Google or files
 * by itself): list the music files, make a request to Google, download one file, delete one
 * synced file, open Google's sign-in page, and receive the setup text from the PC.
 */
final class HostJobs {
    interface Reply { void done(boolean ok, Object value); }
    interface PairListener { void got(String text); }

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final PairListener pairListener;
    private ServerSocket pairServer;

    HostJobs(Context ctx, PairListener pairListener) {
        this.ctx = ctx.getApplicationContext();
        this.pairListener = pairListener;
    }

    private static boolean allowed(String raw) {
        try {
            URL u = new URL(raw);
            String h = u.getHost();
            return u.getProtocol().equals("https") && (h.equals("oauth2.googleapis.com") || h.equals("www.googleapis.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> headers(JSONObject m) {
        Map<String, String> out = new HashMap<>();
        JSONObject h = m.optJSONObject("headers");
        if (h != null) {
            Iterator<String> it = h.keys();
            while (it.hasNext()) { String k = it.next(); out.put(k, h.optString(k)); }
        }
        return out;
    }

    void handle(JSONObject m, Reply reply) {
        pool.execute(() -> {
            try {
                run(m, reply);
            } catch (Exception e) {
                reply.done(false, e.getMessage() == null ? "failed" : e.getMessage());
            }
        });
    }

    private void run(JSONObject m, Reply reply) throws Exception {
        String op = m.optString("op", "");
        LocalLibrary lib = Lunar.local;
        switch (op) {
            case "files":
                reply.done(true, lib.listForSync());
                return;
            case "open": {
                String url = m.optString("url", "");
                if (!url.equals("https://www.google.com/device")) { reply.done(false, "not allowed"); return; }
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                reply.done(true, new JSONObject());
                return;
            }
            case "delete": {
                File f = lib.syncFile(m.optString("root", null), m.optString("file", null));
                if (f == null) { reply.done(false, "bad file"); return; }
                f.delete();
                reply.done(true, new JSONObject());
                return;
            }
            case "http": {
                String url = m.optString("url", "");
                if (!allowed(url)) { reply.done(false, "address not allowed"); return; }
                String body = m.optString("body", "");
                Net.Resp r;
                try {
                    r = Net.request(url, m.optString("method", "GET"), headers(m),
                            body.isEmpty() ? null : body.getBytes(StandardCharsets.UTF_8), 120000);
                } catch (Exception e) {
                    reply.done(false, "no connection: " + e.getMessage());
                    return;
                }
                reply.done(true, new JSONObject().put("status", r.status).put("body", r.text()).put("location", r.location));
                return;
            }
            case "download": {
                File dest = lib.syncFile(m.optString("root", null), m.optString("file", null));
                if (dest == null) { reply.done(false, "bad file"); return; }
                String url = m.optString("url", "");
                if (!allowed(url)) { reply.done(false, "address not allowed"); return; }
                int status;
                try {
                    status = Net.download(url, headers(m), dest);
                } catch (Exception e) {
                    reply.done(false, "no connection: " + e.getMessage());
                    return;
                }
                if (status == 200) reply.done(true, new JSONObject().put("status", 200).put("size", dest.length()));
                else reply.done(true, new JSONObject().put("status", status).put("body", ""));
                return;
            }
            case "pair":
                reply.done(true, new JSONObject().put("url", startPairing()));
                return;
            default:
                reply.done(false, "unknown request");     // sending files up is the PC's job
        }
    }

    // ---------- "Get setup from my PC" ----------
    // For a few minutes the headset answers on the home network at http://<its address>:47900.
    // That page has one box: paste the setup text from Lunar Music on the PC and press Send.

    /** The headset's address on the home network (a 192.168.x.x-style one if there is one). */
    private static String wifiAddress() throws Exception {
        String other = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                if (a.isSiteLocalAddress()) return a.getHostAddress();
                if (other == null) other = a.getHostAddress();
            }
        }
        return other;
    }

    private synchronized String startPairing() throws Exception {
        String ip = wifiAddress();
        if (ip == null) throw new Exception("no Wi-Fi address");
        if (pairServer == null || pairServer.isClosed()) {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            try {
                s.bind(new InetSocketAddress(47900));
            } catch (Exception e) {
                s.bind(new InetSocketAddress(0));
            }
            pairServer = s;
            Thread t = new Thread(() -> serve(s), "lunar-pair");
            t.setDaemon(true);
            t.start();
            Thread stop = new Thread(() -> {            // stop listening after 10 minutes
                try { Thread.sleep(10 * 60 * 1000); } catch (InterruptedException ignored) { }
                closePairing(s);
            }, "lunar-pair-timer");
            stop.setDaemon(true);
            stop.start();
        }
        return "http://" + ip + ":" + pairServer.getLocalPort();
    }

    private synchronized void closePairing(ServerSocket s) {
        try { s.close(); } catch (Exception ignored) { }
        if (pairServer == s) pairServer = null;
    }

    private void serve(ServerSocket s) {
        while (!s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(15000);
                if (answer(c)) closePairing(s);
            } catch (Exception ignored) {
            }
        }
    }

    private static final String PAGE_HEAD = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>Lunar Music for Quest</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(120% 80% at 80% 0%,#2a2f6b,#0a0f26 55%,#04060f);"
            + "color:#eef1fb;font:16px 'Segoe UI',system-ui,sans-serif}.card{width:min(560px,90vw);padding:28px;border-radius:26px;background:rgba(184,198,255,.1);"
            + "border:1px solid rgba(225,232,255,.16);box-shadow:inset 0 1px 0 rgba(240,244,255,.38),0 24px 60px -18px #000a}h1{margin:0 0 6px;font-weight:300;letter-spacing:.3em;font-size:18px}"
            + "p{color:#a5a9b7;line-height:1.5}textarea{box-sizing:border-box;width:100%;height:120px;border-radius:14px;border:1px solid rgba(225,232,255,.2);background:rgba(5,8,22,.55);"
            + "color:#eef1fb;padding:12px;font:13px Consolas,monospace}button{margin-top:14px;padding:11px 26px;border:0;border-radius:99px;background:linear-gradient(#fff,#e3e8ff 40%,#8f9bcc);"
            + "color:#0a0f26;font-weight:600;font-size:15px;cursor:pointer}</style></head><body><div class=card><h1>LUNAR MUSIC</h1>";

    /** Returns true once the setup text has arrived. */
    private boolean answer(Socket c) throws Exception {
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String first = r.readLine();
        if (first == null) return false;
        int length = 0;
        String line;
        while ((line = r.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { length = Integer.parseInt(line.substring(colon + 1).trim()); } catch (Exception ignored) { }
            }
        }
        String html;
        boolean got = false;
        if (first.startsWith("POST") && length > 0 && length < 100000) {
            char[] buf = new char[length];
            int n = 0;
            while (n < length) { int k = r.read(buf, n, length - n); if (k <= 0) break; n += k; }
            String body = new String(new String(buf, 0, n).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            String text = "";
            for (String part : body.split("&")) {
                if (part.startsWith("text=")) text = URLDecoder.decode(part.substring(5), "UTF-8");
            }
            text = text.trim();
            if (!text.isEmpty()) {
                got = true;
                pairListener.got(text);
                html = PAGE_HEAD + "<p>Sent! Put the headset back on: the setup is filled in. Press <b>Sign in with Google</b> there.</p></div></body></html>";
            } else {
                html = PAGE_HEAD + "<p>The box was empty. Go back and paste the setup text.</p></div></body></html>";
            }
        } else {
            html = PAGE_HEAD + "<p>In Lunar Music on your PC, open Settings &gt; Sync with Google Drive and press <b>Copy setup for my phone</b>. Paste it here and press Send.</p>"
                    + "<form method=post><textarea name=text autofocus placeholder='Paste the setup text here'></textarea><button type=submit>Send to my Quest</button></form></div></body></html>";
        }
        byte[] out = html.getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        o.write(out);
        o.flush();
        return got;
    }
}
