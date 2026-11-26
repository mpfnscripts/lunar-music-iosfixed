package com.mpfn.lunarmusic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays straight from Google Drive (Quest): reads the "Lunar Music" folder that the PC fills
 * (SONGS, ICONS, LYRICS) and hands out what the player and the page need. Songs are streamed
 * while they play and never saved on the headset; icons, lyrics and tags are small and kept in
 * the app's cache folder, which Android can clear at any time.
 */
final class Drive {
    // (the properties are only ever set by the developer's own tests)
    static final String API = System.getProperty("lunar.google.api", "https://www.googleapis.com");
    static final String OAUTH = System.getProperty("lunar.google.oauth", "https://oauth2.googleapis.com");

    static final class Item {
        final String id;
        final long size;
        Item(String id, long size) { this.id = id; this.size = size; }
    }

    private final SharedPreferences prefs;
    private final File listFile;
    private final File cacheDir;

    private boolean on;
    private String clientId = "", secret = "", refresh = "";
    private String token = "";
    private long tokenUntil = 0;

    private Map<String, Item> songs = new HashMap<>(), icons = new HashMap<>(), lyrics = new HashMap<>();
    private boolean listed = false, noFolder = false;
    private long listedAt = 0;
    private String listError = null;

    Drive(Context ctx) {
        prefs = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE);
        listFile = new File(ctx.getFilesDir(), "drive-list.json");
        cacheDir = new File(ctx.getCacheDir(), "drive");
        on = prefs.getBoolean("on", false);
        clientId = prefs.getString("clientId", "");
        secret = prefs.getString("secret", "");
        refresh = prefs.getString("refresh", "");
        loadList();
    }

    synchronized boolean isOn() { return on && !refresh.isEmpty() && !clientId.isEmpty(); }

    /** Called when the page's sign-in or "play from Drive" choice changes. True if anything changed. */
    synchronized boolean configure(boolean on, String id, String sec, String ref) {
        if (this.on == on && clientId.equals(id) && secret.equals(sec) && refresh.equals(ref)) return false;
        boolean sameAccount = refresh.equals(ref) && clientId.equals(id);
        this.on = on;
        clientId = id;
        secret = sec;
        refresh = ref;
        prefs.edit().putBoolean("on", on).putString("clientId", id).putString("secret", sec).putString("refresh", ref).apply();
        token = "";
        tokenUntil = 0;
        if (!sameAccount) {
            songs = new HashMap<>();
            icons = new HashMap<>();
            lyrics = new HashMap<>();
            listed = false;
            noFolder = false;
            listError = null;
            listFile.delete();
        }
        return true;
    }

    // ---------- sign-in ----------

    synchronized String token() throws IOException {
        if (!token.isEmpty() && System.currentTimeMillis() < tokenUntil) return token;
        String form = "client_id=" + Net.enc(clientId) + "&client_secret=" + Net.enc(secret)
                + "&refresh_token=" + Net.enc(refresh) + "&grant_type=refresh_token";
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Net.Resp r = Net.request(OAUTH + "/token", "POST", h, form.getBytes(StandardCharsets.UTF_8), 30000);
        JSONObject j = json(r);
        String t = j.optString("access_token", "");
        if (t.isEmpty()) {
            String e = j.optString("error", "");
            throw new IOException(e.equals("invalid_grant") ? "Google asked you to sign in again"
                    : "Google did not renew the sign-in" + (e.isEmpty() ? " (" + r.status + ")" : " (" + e + ")"));
        }
        token = t;
        // renew well before it runs out, so a song that starts now can finish on the same pass
        tokenUntil = System.currentTimeMillis() + Math.max(60, j.optLong("expires_in", 3600) - 900) * 1000L;
        return token;
    }

    Map<String, String> authHeaders() throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token());
        return h;
    }

    private static JSONObject json(Net.Resp r) {
        try {
            return new JSONObject(r.text());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject get(String path) throws IOException {
        Net.Resp r = Net.request(API + path, "GET", authHeaders(), null, 30000);
        if (r.status == 401) {
            synchronized (this) { token = ""; }
            r = Net.request(API + path, "GET", authHeaders(), null, 30000);
        }
        if (r.status >= 400) {
            String msg = json(r).optJSONObject("error") != null ? json(r).optJSONObject("error").optString("message", "") : "";
            throw new IOException("Google Drive said " + r.status + (msg.isEmpty() ? "" : ": " + msg));
        }
        return json(r);
    }

    // ---------- the song list ----------

    private String folder(String name, String parent) throws IOException {
        String q = "name='" + name + "' and mimeType='application/vnd.google-apps.folder' and trashed=false"
                + (parent != null ? " and '" + parent + "' in parents" : "");
        JSONArray files = get("/drive/v3/files?fields=" + Net.enc("files(id,name)") + "&q=" + Net.enc(q)).optJSONArray("files");
        return files != null && files.length() > 0 ? files.optJSONObject(0).optString("id", null) : null;
    }

    private Map<String, Item> list(String folder) throws IOException {
        Map<String, Item> out = new HashMap<>();
        if (folder == null) return out;
        String page = "";
        do {
            String q = "'" + folder + "' in parents and trashed=false";
            JSONObject j = get("/drive/v3/files?pageSize=1000&fields=" + Net.enc("nextPageToken,files(id,name,size)")
                    + "&q=" + Net.enc(q) + (page.isEmpty() ? "" : "&pageToken=" + Net.enc(page)));
            JSONArray files = j.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                String name = f.optString("name", "");
                if (!name.isEmpty() && !out.containsKey(name)) out.put(name, new Item(f.optString("id"), f.optLong("size", 0)));
            }
            page = j.optString("nextPageToken", "");
        } while (!page.isEmpty());
        return out;
    }

    /** Reads the folder listing from Drive (or keeps the saved one when `fresh` is false). */
    synchronized void relist(boolean fresh) {
        if (!isOn()) return;
        if (listed && !fresh) return;
        if (listed && System.currentTimeMillis() - listedAt < 4000) return;     // just did it
        try {
            String top = folder("Lunar Music", null);
            if (top == null) {
                noFolder = true;
                songs = new HashMap<>();
                icons = new HashMap<>();
                lyrics = new HashMap<>();
            } else {
                noFolder = false;
                songs = list(folder("SONGS", top));
                icons = list(folder("ICONS", top));
                lyrics = list(folder("LYRICS", top));
            }
            listed = true;
            listedAt = System.currentTimeMillis();
            listError = null;
            saveList();
        } catch (Exception e) {
            listError = e.getMessage() == null ? "no connection" : e.getMessage();
        }
    }

    private static JSONObject toJson(Map<String, Item> m) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Item> e : m.entrySet()) o.put(e.getKey(), new JSONArray().put(e.getValue().id).put(e.getValue().size));
        return o;
    }

    private static Map<String, Item> fromJson(JSONObject o) {
        Map<String, Item> m = new HashMap<>();
        if (o == null) return m;
        JSONArray names = o.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String n = names.optString(i);
            JSONArray a = o.optJSONArray(n);
            if (a != null) m.put(n, new Item(a.optString(0), a.optLong(1)));
        }
        return m;
    }

    private void saveList() {
        try {
            JSONObject o = new JSONObject();
            o.put("songs", toJson(songs)).put("icons", toJson(icons)).put("lyrics", toJson(lyrics)).put("noFolder", noFolder);
            try (FileOutputStream out = new FileOutputStream(listFile)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {
        }
    }

    private void loadList() {
        if (!listFile.exists()) return;
        try (FileInputStream in = new FileInputStream(listFile)) {
            JSONObject o = new JSONObject(new String(Net.readAll(in, 32L * 1024 * 1024), StandardCharsets.UTF_8));
            songs = fromJson(o.optJSONObject("songs"));
            icons = fromJson(o.optJSONObject("icons"));
            lyrics = fromJson(o.optJSONObject("lyrics"));
            noFolder = o.optBoolean("noFolder", false);
            listed = true;          // shown straight away; refreshed from Drive soon after
            listedAt = 0;
        } catch (Exception ignored) {
        }
    }

    static String baseName(String rel) {
        String last = rel.substring(Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\')) + 1);
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    static String key(String name) { return name.trim().toLowerCase(Locale.ROOT); }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String iconFor(String song) {
        String want = key(baseName(song)), fallback = null;
        List<String> names = new ArrayList<>(icons.keySet());
        Collections.sort(names);
        for (String n : names) {
            if (!ext(n).equals("png")) continue;
            String k = key(baseName(n));
            if (k.equals(want)) return n;
            if (k.equals("default") && fallback == null) fallback = n;
        }
        return fallback;
    }

    /** The answer for /api/library while playing from Drive. */
    synchronized JSONObject library() throws Exception {
        JSONArray list = new JSONArray();
        List<String> names = new ArrayList<>();
        for (String n : songs.keySet()) if (ext(n).equals("mp3")) names.add(n);
        Collections.sort(names, (a, b) -> baseName(a).compareToIgnoreCase(baseName(b)));
        for (String n : names) {
            int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
            String icon = iconFor(n);
            list.put(new JSONObject().put("file", n).put("name", baseName(n)).put("folder", cut > 0 ? n.substring(0, cut) : "")
                    .put("icon", icon == null ? JSONObject.NULL : icon));
        }
        JSONObject out = new JSONObject().put("songs", list).put("songsPath", "Google Drive > Lunar Music > SONGS")
                .put("iconsPath", "Google Drive > Lunar Music > ICONS").put("songsFound", true).put("drive", true);
        if (names.isEmpty()) {
            if (!listed && listError != null) {
                out.put("emptyHead", "Could not reach Google Drive")
                   .put("emptyBody", "Check that the headset is online, then press the refresh button. (" + listError + ")");
            } else if (noFolder) {
                out.put("emptyHead", "Nothing in Google Drive yet")
                   .put("emptyBody", "On your PC, open Settings > Sync with Google Drive, choose This PC sends its music and press Sync now. Then press the refresh button here.");
            } else {
                out.put("emptyHead", "No songs in Google Drive yet")
                   .put("emptyBody", "Sync from your PC (Settings > Sync with Google Drive > Sync now), then press the refresh button here.");
            }
        }
        if (listError != null) out.put("driveError", listError);
        return out;
    }

    synchronized String songId(String file) {
        Item it = songs.get(file);
        return it == null ? null : it.id;
    }

    String streamUrl(String id) { return API + "/drive/v3/files/" + id + "?alt=media"; }

    // ---------- icons, lyrics and tags (small, cached) ----------

    private byte[] cached(String name, String id, String range, long max) throws IOException {
        File f = new File(cacheDir, name);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) { return Net.readAll(in, max); }
        }
        Map<String, String> h = authHeaders();
        if (range != null) h.put("Range", range);
        Net.Resp r = Net.request(streamUrl(id), "GET", h, null, 60000);
        if (r.status != 200 && r.status != 206) throw new IOException("Google Drive said " + r.status);
        byte[] data = r.body.length > max ? java.util.Arrays.copyOf(r.body, (int) max) : r.body;
        cacheDir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); } catch (IOException ignored) { }
        return data;
    }

    byte[] icon(String name) throws IOException {
        Item it;
        synchronized (this) { it = icons.get(name); }
        if (it == null) return null;
        return cached("icon-" + it.id, it.id, null, 8L * 1024 * 1024);
    }

    String lyricsFor(String song) throws IOException {
        String want = key(baseName(song)), pick = null;
        Item it = null;
        synchronized (this) {
            for (String e : new String[]{"lrc", "txt"}) {
                List<String> names = new ArrayList<>(lyrics.keySet());
                Collections.sort(names);
                for (String n : names) if (pick == null && ext(n).equals(e) && key(baseName(n)).equals(want)) pick = n;
            }
            if (pick != null) it = lyrics.get(pick);
        }
        if (it == null) return null;
        return LocalLibrary.decodeText(cached("lyr-" + it.id, it.id, null, 2_000_000));
    }

    /** The ID3 tag at the start of a song (embedded lyrics live there), or null. */
    byte[] id3(String song) throws IOException {
        String id = songId(song);
        if (id == null) return null;
        byte[] head = cached("head-" + id, id, "bytes=0-9", 10);
        if (head.length < 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;
        int size = ((head[6] & 0x7f) << 21) | ((head[7] & 0x7f) << 14) | ((head[8] & 0x7f) << 7) | (head[9] & 0x7f);
        if (size <= 0 || size > 4_000_000) return null;
        return cached("id3-" + id, id, "bytes=0-" + (size + 9), size + 10);
    }
}
