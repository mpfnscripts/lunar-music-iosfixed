package com.mpfn.lunarmusic;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays the music. Owns the queue so songs keep advancing while the window is closed, the same
 * way the iPhone app does. Songs come from Google Drive (streamed) or from the headset.
 */
final class Player {
    interface Listener { void event(JSONObject ev); }

    Listener listener;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AudioManager audioManager;
    private final MediaSession session;
    private AudioFocusRequest focus;

    private MediaPlayer mp;
    private boolean prepared = false;
    private List<String> queue = new ArrayList<>();
    private int position = -1;
    private String repeat = "off";
    private float volume = 1f;
    private boolean wantsPlaying = false;
    private int failures = 0;
    private String currentFile;
    private int loadSeq = 0;
    private int pendingSeekMs = -1;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            main.postDelayed(this, 250);
        }
    };

    Player(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        audioManager = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(this.ctx, "LunarMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seek(pos / 1000.0); }
            @Override public void onStop() { pause(); }
        });
        session.setActive(true);
    }

    // ---------- queue ----------

    void setQueue(List<String> files, String mode) {
        queue = files;
        repeat = mode;
        position = currentFile == null ? -1 : files.indexOf(currentFile);
    }

    void setRepeat(String mode) { repeat = mode; }

    void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        if (mp != null && prepared) mp.setVolume(volume, volume);
    }

    // ---------- transport ----------

    void load(int p, boolean autoplay) {
        if (p < 0 || p >= queue.size()) return;
        position = p;
        final String file = queue.get(p);
        currentFile = file;
        release();
        wantsPlaying = autoplay;
        pendingSeekMs = -1;
        final int seq = ++loadSeq;
        updateSession();
        io.execute(() -> {
            Source src = null;
            String problem = null;
            try {
                src = Lunar.source(file);
            } catch (Exception e) {
                problem = e.getMessage();
            }
            final Source found = src;
            final String why = problem;
            main.post(() -> {
                if (seq != loadSeq) return;
                if (found == null) failed(file, why);
                else start(found, file, seq);
            });
        });
    }

    private void start(Source src, String file, int seq) {
        MediaPlayer m = new MediaPlayer();
        mp = m;
        prepared = false;
        m.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        m.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
        m.setOnPreparedListener(x -> {
            if (mp != x) return;
            prepared = true;
            x.setVolume(volume, volume);
            if (pendingSeekMs >= 0) { x.seekTo(pendingSeekMs); pendingSeekMs = -1; }
            emit(ev("track").put2("file", file).put2("pos", position).put2("d", duration()));
            if (wantsPlaying) reallyPlay();
            else { emitState(); updateSession(); }
        });
        m.setOnCompletionListener(x -> { if (mp == x) next(true); });
        m.setOnErrorListener((x, what, extra) -> {
            if (mp == x) failed(file, "the song could not be played (" + what + "/" + extra + ")");
            return true;
        });
        try {
            if (src.url != null) m.setDataSource(ctx, Uri.parse(src.url), src.headers);
            else m.setDataSource(src.path);
            m.prepareAsync();
        } catch (Exception e) {
            failed(file, e.getMessage());
        }
    }

    /** A song that cannot be played: skip ahead, but give up if several in a row fail. */
    private void failed(String file, String why) {
        release();
        failures++;
        if (failures == 1) emit(ev("toast").put2("text", "Could not play " + Drive.baseName(file) + (why != null && !why.isEmpty() ? ": " + why : "")));
        if (failures < Math.min(5, queue.size())) {
            next(true);
        } else {
            wantsPlaying = false;
            stopTicker();
            emitState();
            updateSession();
            PlayerService.playing(ctx, false, "", "");
        }
    }

    private void release() {
        stopTicker();
        if (mp != null) {
            MediaPlayer old = mp;
            mp = null;
            try { old.reset(); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
        prepared = false;
    }

    void play() {
        if (mp == null) {
            if (!queue.isEmpty()) load(Math.max(position, 0), true);
            return;
        }
        if (!prepared) { wantsPlaying = true; return; }
        reallyPlay();
    }

    private void reallyPlay() {
        if (mp == null || !prepared) return;
        requestFocus();
        wantsPlaying = true;
        try { mp.start(); failures = 0; } catch (Exception ignored) { }
        startTicker();
        emitState();
        updateSession();
    }

    void pause() {
        wantsPlaying = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        stopTicker();
        emitState();
        updateSession();
    }

    void toggle() { if (isPlaying()) pause(); else play(); }

    void seek(double t) {
        int ms = (int) Math.max(0, t * 1000);
        if (mp != null && prepared) mp.seekTo(ms);
        else pendingSeekMs = ms;
        updateSession();
    }

    void next(boolean auto) {
        if (queue.isEmpty()) return;
        if (auto && "one".equals(repeat) && mp != null && prepared) {
            mp.seekTo(0);
            reallyPlay();
            return;
        }
        if (position < queue.size() - 1) {
            load(position + 1, true);
        } else if (auto && "off".equals(repeat)) {
            wantsPlaying = false;
            stopTicker();
            emitState();
            updateSession();
        } else {
            load(0, true);
        }
    }

    void previous() {
        if (queue.isEmpty()) return;
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) {
            mp.seekTo(0);
            reallyPlay();
            return;
        }
        load((Math.max(position, 0) - 1 + queue.size()) % queue.size(), true);
    }

    boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    private double now() {
        try { return mp != null && prepared ? mp.getCurrentPosition() / 1000.0 : 0; } catch (Exception e) { return 0; }
    }

    private double duration() {
        try {
            int d = mp != null && prepared ? mp.getDuration() : 0;
            return d > 0 ? d / 1000.0 : 0;
        } catch (Exception e) { return 0; }
    }

    // ---------- reporting to the page ----------

    static final class Ev extends JSONObject {
        Ev put2(String k, Object v) {
            try { put(k, v); } catch (Exception ignored) { }
            return this;
        }
    }

    private static Ev ev(String type) { return new Ev().put2("type", type); }

    private void emit(JSONObject e) { if (listener != null) listener.event(e); }

    JSONObject snapshot() {
        Ev out = ev("sync").put2("playing", isPlaying());
        if (currentFile != null && mp != null) {
            out.put2("file", currentFile).put2("pos", position).put2("t", now()).put2("d", duration());
        }
        return out;
    }

    private void emitState() {
        boolean on = isPlaying();
        emit(ev("state").put2("playing", on));
        PlayerService.playing(ctx, on || wantsPlaying, title(), artist());
    }

    private void startTicker() { main.removeCallbacks(ticker); main.post(ticker); }

    private void stopTicker() { main.removeCallbacks(ticker); }

    private void tick() {
        if (mp == null || !prepared) return;
        boolean on = isPlaying();
        // There is no sound level to measure here, so the ring of light gets a steady glow while playing.
        emit(ev("tick").put2("t", now()).put2("d", duration()).put2("level", on ? 0.32 : 0));
    }

    // ---------- headset media controls ----------

    private String title() {
        if (currentFile == null) return "";
        String n = Drive.baseName(currentFile);
        int i = n.indexOf(" - ");
        return i > 0 ? n.substring(i + 3).trim() : n;
    }

    private String artist() {
        if (currentFile == null) return "";
        String n = Drive.baseName(currentFile);
        int i = n.indexOf(" - ");
        return i > 0 ? n.substring(0, i).trim() : "";
    }

    private void updateSession() {
        try {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (duration() * 1000)).build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                    .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, (long) (now() * 1000), isPlaying() ? 1f : 0f)
                    .build());
        } catch (Exception ignored) { }
    }

    private void requestFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> main.post(() -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            if (isPlaying()) pause();
                        }
                    })).build();
        }
        try { audioManager.requestAudioFocus(focus); } catch (Exception ignored) { }
    }

    /** Where a song's sound comes from: a web address (with sign-in headers) or a file. */
    static final class Source {
        final String url;
        final Map<String, String> headers;
        final String path;

        Source(String url, Map<String, String> headers) { this.url = url; this.headers = headers; this.path = null; }
        Source(File f) { this.url = null; this.headers = null; this.path = f.getAbsolutePath(); }
    }
}
