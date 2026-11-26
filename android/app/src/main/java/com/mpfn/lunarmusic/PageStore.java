package com.mpfn.lunarmusic;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Almost all of Lunar Music lives in one page (app.html). The app ships with a copy and also
 * downloads the newest copy from GitHub, so new features arrive without reinstalling. Updates only
 * ever move forward, and a page that needs newer app code than this build has is skipped.
 */
final class PageStore {
    /** Raise this, and "lunar-native" in app.html, when the page starts needing new app code. */
    static final int NATIVE_LEVEL = 1;
    static final String REMOTE = "https://raw.githubusercontent.com/mpfnscripts/lunar-music-iosfixed/main/LunarMusic/app.html";

    static final int NONE = 0, UPDATED = 1, NEEDS_REINSTALL = 2;

    private PageStore() {}

    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "page.html"); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("page", Context.MODE_PRIVATE); }

    static String sha(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] bundled(Context c) {
        try (InputStream in = c.getAssets().open("app.html")) {
            return Net.readAll(in, 16L * 1024 * 1024);
        } catch (Exception e) {
            return null;
        }
    }

    /** The level of app code a page asks for, or -1 if it does not look like a Lunar Music page. */
    static int requiredLevel(byte[] data) {
        if (data == null || data.length < 20000) return -1;
        String t = new String(data, StandardCharsets.UTF_8);
        if (!t.contains("<title>Lunar Music</title>")) return -1;
        String mark = "name=\"lunar-native\" content=\"";
        int i = t.indexOf(mark);
        if (i < 0) return -1;
        int j = i + mark.length(), n = 0;
        boolean any = false;
        while (j < t.length() && Character.isDigit(t.charAt(j))) { n = n * 10 + (t.charAt(j) - '0'); j++; any = true; }
        return any ? n : -1;
    }

    /** "2.9" -> 2009. Pages without the mark count as 0. */
    static int version(byte[] data) {
        if (data == null) return 0;
        String t = new String(data, StandardCharsets.UTF_8);
        String mark = "name=\"lunar-version\" content=\"";
        int i = t.indexOf(mark);
        if (i < 0) return 0;
        int end = t.indexOf('"', i + mark.length());
        if (end < 0) return 0;
        String[] parts = t.substring(i + mark.length(), end).split("\\.", 2);
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return major * 1000 + minor;
        } catch (Exception e) {
            return 0;
        }
    }

    static byte[] current(Context c) {
        byte[] shipped = bundled(c);
        if (shipped == null) return null;
        File f = cacheFile(c);
        if (sha(shipped).equals(prefs(c).getString("pageForBuild", "")) && f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] cached = Net.readAll(in, 16L * 1024 * 1024);
                int level = requiredLevel(cached);
                if (level >= 0 && level <= NATIVE_LEVEL && version(cached) >= version(shipped)) return cached;
            } catch (Exception ignored) { }
        }
        return shipped;
    }

    /** Looks for a newer page on GitHub (call off the main thread). */
    static int check(Context c) {
        try {
            Net.Resp r = Net.request(REMOTE, "GET", null, null, 20000);
            if (r.status != 200) return NONE;
            byte[] data = r.body, shipped = bundled(c), showing = current(c);
            int level = requiredLevel(data);
            if (level < 0 || shipped == null || showing == null) return NONE;
            if (sha(data).equals(sha(showing)) || version(data) <= version(showing)) return NONE;   // never go backwards
            if (level > NATIVE_LEVEL) return NEEDS_REINSTALL;
            try (FileOutputStream out = new FileOutputStream(cacheFile(c))) { out.write(data); }
            prefs(c).edit().putString("pageForBuild", sha(shipped)).apply();
            return UPDATED;
        } catch (Exception e) {
            return NONE;
        }
    }
}
