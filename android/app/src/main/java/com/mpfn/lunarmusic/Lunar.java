package com.mpfn.lunarmusic;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/** The parts that live as long as the app does (the music keeps playing when the window closes). */
final class Lunar {
    static Player player;
    static Drive drive;
    static LocalLibrary local;

    private Lunar() {}

    /** Call on the main thread. */
    static synchronized void init(Context c) {
        if (player != null) return;
        Context app = c.getApplicationContext();
        local = new LocalLibrary(app);
        drive = new Drive(app);
        player = new Player(app);
    }

    /** Where to play a song from (runs off the main thread: may renew the Google sign-in). */
    static Player.Source source(String file) throws IOException {
        if (drive.isOn()) {
            String id = drive.songId(file);
            if (id == null) throw new IOException("it is no longer in Google Drive");
            return new Player.Source(drive.streamUrl(id), drive.authHeaders());
        }
        File f = local.safeFile(file);
        if (f == null) throw new IOException("the file is missing");
        return new Player.Source(f);
    }
}
