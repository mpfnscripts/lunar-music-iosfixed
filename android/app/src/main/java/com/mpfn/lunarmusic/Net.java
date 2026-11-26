package com.mpfn.lunarmusic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Small HTTP helper (no libraries needed). */
final class Net {
    private Net() {}

    static final class Resp {
        int status;
        byte[] body = new byte[0];
        String location = "";

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    private static HttpURLConnection open(String url, String method, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
        if (m.equals("PATCH")) {            // HttpURLConnection has no PATCH; Google accepts this instead
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(m);
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        }
        return c;
    }

    private static void writeBody(HttpURLConnection c, byte[] body) throws IOException {
        if (body == null) return;
        String m = c.getRequestMethod();
        if (m.equals("GET") || m.equals("DELETE")) return;
        c.setDoOutput(true);        // (not streamed: then error answers such as 401 can still be read)
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
    }

    static Resp request(String url, String method, Map<String, String> headers, byte[] body, int readTimeoutMs) throws IOException {
        HttpURLConnection c = open(url, method, headers, readTimeoutMs);
        try {
            writeBody(c, body);
            Resp r = new Resp();
            r.status = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            r.location = loc == null ? "" : loc;
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream s = in) { r.body = readAll(s, 64L * 1024 * 1024); }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Downloads into `dest` (through a temporary file). Returns the HTTP status. */
    static int download(String url, Map<String, String> headers, File dest) throws IOException {
        HttpURLConnection c = open(url, "GET", headers, 120000);
        try {
            int status = c.getResponseCode();
            if (status != 200) return status;
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (dest.exists() && !dest.delete()) throw new IOException("cannot replace the file");
            if (!tmp.renameTo(dest)) throw new IOException("cannot save the file");
            return status;
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("answer too big");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
