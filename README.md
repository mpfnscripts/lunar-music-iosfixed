# Lunar Music for iPhone

A glassy, lunar music player that plays the MP3s you put in
**Files > On My iPhone > Lunar Music**.

- `SONGS` - your `.mp3` files
- `ICONS` - `.png` artwork with the same name as the song (`Moonrise.mp3` -> `Moonrise.png`);
  `default.png` is used for songs without their own
- `LYRICS` - `.lrc` (timed) or `.txt` lyrics with the same name as the song; lyrics stored
  inside the MP3 itself are picked up too

## Getting the IPA (no Mac needed)

1. Create a free GitHub account and a new **private** repository.
2. Upload everything in this folder, including the hidden `.github` folder.
3. Open the repository's **Actions** tab. The "Build Lunar Music IPA" run starts by itself
   (or press **Run workflow**). It takes a few minutes.
4. Open the finished run and download **LunarMusic-ipa** from the Artifacts section.
   Unzip it to get `LunarMusic.ipa`.

The IPA is unsigned. Install it with a sideloading tool such as Sideloadly or AltStore,
which signs it with your Apple ID.

## What is in here

- `LunarMusic/app.html` - the player screen (shared with the Windows version)
- `LunarMusic/App.swift` - app start-up, the music folder, and the bridge to the screen
- `LunarMusic/Player.swift` - playback, queue, lock-screen controls
- `project.yml` - recipe XcodeGen turns into an Xcode project
- `.github/workflows/build.yml` - the cloud build

## Updates

The app checks this repository for a newer `LunarMusic/app.html` whenever it is opened and
uses it automatically, so most new features arrive without reinstalling. This only works while
the repository is public. Changes to the Swift files still need a new IPA.

## Sync

With a one-time Google setup, a PC running Lunar Music sends its songs, icons and lyrics to a
private "Lunar Music" folder in your own Google Drive, and this app downloads them into
SONGS, ICONS and LYRICS. Set it up under Settings > Sync with Google Drive.
