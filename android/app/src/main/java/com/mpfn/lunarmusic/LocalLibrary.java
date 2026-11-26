package com.mpfn.lunarmusic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Music stored on the headset itself, used when Google Drive is not hooked up (or when the user
 * picks "Download to this headset"). The folder is Android/data/com.mpfn.lunarmusic/files, which
 * a PC can open over USB; inside are SONGS, ICONS and LYRICS, like on the PC.
 */
final class LocalLibrary {
    final File root;

    LocalLibrary(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        root = ext != null ? ext : new File(ctx.getFilesDir(), "music");
        for (String n : new String[]{"SONGS", "ICONS", "LYRICS"}) new File(root, n).mkdirs();
    }

    File folder(String rootName) {
        switch (rootName == null ? "" : rootName) {
            case "songs": return new File(root, "SONGS");
            case "icons": return new File(root, "ICONS");
            case "lyrics": return new File(root, "LYRICS");
            default: return null;
        }
    }

    static String[] extensions(String rootName) {
        switch (rootName == null ? "" : rootName) {
            case "songs": return new String[]{"mp3"};
            case "icons": return new String[]{"png"};
            case "lyrics": return new String[]{"lrc", "txt"};
            default: return new String[0];
        }
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Every file under `dir` with one of these extensions, as paths relative to it ("/" separated). */
    static List<String> walk(File dir, String... exts) {
        List<String> out = new ArrayList<>();
        walk(dir, "", out, exts);
        Collections.sort(out);
        return out;
    }

    private static void walk(File dir, String prefix, List<String> out, String[] exts) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            String rel = prefix + f.getName();
            if (f.isDirectory()) {
                walk(f, rel + "/", out, exts);
            } else {
                String e = ext(f.getName());
                for (String want : exts) if (e.equals(want)) { out.add(rel); break; }
            }
        }
    }

    private Map<String, String> iconMap() {
        Map<String, String> map = new HashMap<>();
        for (String p : walk(root, "png")) {
            String k = Drive.key(Drive.baseName(p));
            if (!map.containsKey(k)) map.put(k, p);
        }
        return map;
    }

    String iconPath(String song) {
        Map<String, String> m = iconMap();
        String hit = m.get(Drive.key(Drive.baseName(song)));
        return hit != null ? hit : m.get("default");
    }

    JSONObject library() throws Exception {
        Map<String, String> icons = iconMap();
        List<String> files = walk(root, "mp3");
        Collections.sort(files, (a, b) -> Drive.baseName(a).compareToIgnoreCase(Drive.baseName(b)));
        JSONArray list = new JSONArray();
        for (String rel : files) {
            String name = Drive.baseName(rel);
            int cut = rel.lastIndexOf('/');
            String folder = cut > 0 ? rel.substring(0, cut) : "";
            if (folder.equalsIgnoreCase("SONGS")) folder = "";
            else if (folder.toUpperCase(Locale.ROOT).startsWith("SONGS/")) folder = folder.substring(6);
            String icon = icons.get(Drive.key(name));
            if (icon == null) icon = icons.get("default");
            list.put(new JSONObject().put("file", rel).put("name", name).put("folder", folder).put("icon", icon == null ? JSONObject.NULL : icon));
        }
        JSONObject out = new JSONObject().put("songs", list)
                .put("songsPath", "Quest > Android > data > com.mpfn.lunarmusic > files > SONGS")
                .put("iconsPath", "Quest > Android > data > com.mpfn.lunarmusic > files > ICONS")
                .put("songsFound", true);
        if (files.isEmpty()) {
            out.put("emptyHead", "No music here yet")
               .put("emptyBody", "To play the music your PC sent to Google Drive, open Settings (top right) > Sync with Google Drive and sign in. "
                       + "Or copy .mp3 files onto the headset over USB, into this folder:");
        }
        return out;
    }

    /** A file inside the music folder, or null if missing or if the path tries to leave it. */
    File safeFile(String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\")) return null;
        for (String part : rel.split("/")) if (part.equals("..")) return null;
        File f = new File(root, rel);
        return f.isFile() ? f : null;
    }

    String lyricsText(String song) throws IOException {
        String want = Drive.key(Drive.baseName(song));
        int cut = song.lastIndexOf('/');
        String songDir = cut > 0 ? song.substring(0, cut) : "";
        List<String> candidates = new ArrayList<>();
        for (String p : walk(root, "lrc")) if (Drive.key(Drive.baseName(p)).equals(want)) candidates.add(p);
        for (String p : walk(root, "txt")) if (Drive.key(Drive.baseName(p)).equals(want)) candidates.add(p);
        String pick = null;
        for (String p : candidates) {
            int c = p.lastIndexOf('/');
            if ((c > 0 ? p.substring(0, c) : "").equals(songDir)) { pick = p; break; }
        }
        if (pick == null && !candidates.isEmpty()) pick = candidates.get(0);
        if (pick == null) return null;
        File f = new File(root, pick);
        if (f.length() > 2_000_000) return null;
        try (FileInputStream in = new FileInputStream(f)) { return decodeText(Net.readAll(in, 2_000_000)); }
    }

    static String decodeText(byte[] data) {
        if (data.length >= 2 && ((data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xFE)) return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        if (data.length >= 2 && ((data[0] & 0xff) == 0xFE && (data[1] & 0xff) == 0xFF)) return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        if (s.contains("�")) s = new String(data, StandardCharsets.ISO_8859_1);
        return s;
    }

    static byte[] id3Tag(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] head = new byte[10];
            if (in.read(head) != 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;
            int size = ((head[6] & 0x7f) << 21) | ((head[7] & 0x7f) << 14) | ((head[8] & 0x7f) << 7) | (head[9] & 0x7f);
            if (size <= 0 || size > 32_000_000) return null;
            byte[] out = new byte[size + 10];
            System.arraycopy(head, 0, out, 0, 10);
            int got = 10;
            while (got < out.length) {
                int n = in.read(out, got, out.length - got);
                if (n <= 0) break;
                got += n;
            }
            return out;
        }
    }

    /** What the page's "download" sync needs: the files in each folder with their sizes. */
    JSONObject listForSync() throws Exception {
        JSONObject out = new JSONObject();
        for (String r : new String[]{"songs", "icons", "lyrics"}) {
            File dir = folder(r);
            JSONArray files = new JSONArray();
            for (String rel : walk(dir, extensions(r))) files.put(new JSONObject().put("file", rel).put("size", new File(dir, rel).length()));
            out.put(r, new JSONObject().put("found", dir.isDirectory()).put("files", files));
        }
        return out;
    }

    /** A file inside one of the three folders, of the right type, for sync to write or delete. */
    File syncFile(String rootName, String rel) {
        File dir = folder(rootName);
        if (dir == null || rel == null || rel.isEmpty() || rel.startsWith("/")) return null;
        String clean = rel.replace('\\', '/');
        for (String part : clean.split("/")) if (part.equals("..")) return null;
        boolean ok = false;
        for (String e : extensions(rootName)) if (ext(clean).equals(e)) ok = true;
        return ok ? new File(dir, clean) : null;
    }
}
