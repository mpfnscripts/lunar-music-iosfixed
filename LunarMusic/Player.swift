// Plays the music. Owns the queue so songs keep advancing while the phone is locked,
// and connects to the lock screen / Control Center / headphone buttons.

import AVFoundation
import MediaPlayer
import UIKit

final class Player: NSObject, AVAudioPlayerDelegate {
    var onEvent: (([String: Any]) -> Void)?

    private var audio: AVAudioPlayer?
    private var queue: [String] = []        // song paths, in play order
    private var position = -1
    private var repeatMode = "off"          // off | all | one
    private var volume: Float = 1
    private var wantsPlaying = false
    private var failures = 0
    private var timer: Timer?
    private var currentFile: String?
    private var artwork: MPMediaItemArtwork?

    override init() {
        super.init()
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        NotificationCenter.default.addObserver(self, selector: #selector(interrupted(_:)),
                                               name: AVAudioSession.interruptionNotification, object: session)
        setupRemoteCommands()
    }

    var isPlaying: Bool { return audio?.isPlaying ?? false }

    // MARK: Queue

    func setQueue(_ files: [String], repeatMode mode: String) {
        queue = files
        repeatMode = mode
        if let file = currentFile {
            position = files.firstIndex(of: file) ?? -1
        } else {
            position = -1
        }
    }

    func setRepeat(_ mode: String) { repeatMode = mode }

    func setVolume(_ v: Float) {
        volume = max(0, min(1, v))
        audio?.volume = volume
    }

    // MARK: Transport

    func load(position p: Int, autoplay: Bool) {
        guard p >= 0, p < queue.count else { return }
        position = p
        let file = queue[p]
        currentFile = file
        stopTimer()
        audio?.stop()

        let url = Library.docs.appendingPathComponent(file)
        guard let newPlayer = try? AVAudioPlayer(contentsOf: url) else {
            // Unplayable file: skip ahead, but give up if several in a row fail.
            audio = nil
            failures += 1
            if failures < min(5, queue.count) {
                next(auto: true)
            } else {
                wantsPlaying = false
                emitState()
            }
            return
        }
        newPlayer.delegate = self
        newPlayer.isMeteringEnabled = true
        newPlayer.volume = volume
        newPlayer.prepareToPlay()
        audio = newPlayer

        artwork = nil
        if let rel = Library.iconPath(forSong: file),
           let image = UIImage(contentsOfFile: Library.docs.appendingPathComponent(rel).path) {
            artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        }

        onEvent?(["type": "track", "file": file, "pos": p, "d": finite(newPlayer.duration)])
        if autoplay {
            play()
        } else {
            wantsPlaying = false
            emitState()
            updateNowPlaying()
        }
    }

    func play() {
        guard let a = audio else {
            if !queue.isEmpty { load(position: max(position, 0), autoplay: true) }
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        wantsPlaying = true
        if a.play() { failures = 0 }
        startTimer()
        emitState()
        updateNowPlaying()
    }

    func pause() {
        wantsPlaying = false
        audio?.pause()
        stopTimer()
        emitState()
        updateNowPlaying()
    }

    func toggle() {
        if audio?.isPlaying == true { pause() } else { play() }
    }

    func seek(_ t: Double) {
        guard let a = audio else { return }
        a.currentTime = max(0, min(t, a.duration))
        updateNowPlaying()
    }

    func next(auto: Bool) {
        guard !queue.isEmpty else { return }
        if auto && repeatMode == "one", let a = audio {
            a.currentTime = 0
            play()
            return
        }
        if position < queue.count - 1 {
            load(position: position + 1, autoplay: true)
        } else if auto && repeatMode == "off" {
            wantsPlaying = false
            stopTimer()
            emitState()
            updateNowPlaying()
        } else {
            load(position: 0, autoplay: true)
        }
    }

    func previous() {
        guard !queue.isEmpty else { return }
        if let a = audio, a.currentTime > 3 {
            a.currentTime = 0
            play()
            return
        }
        load(position: (max(position, 0) - 1 + queue.count) % queue.count, autoplay: true)
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        next(auto: true)
    }

    // MARK: Reporting to the page

    func snapshot() -> [String: Any] {
        var out: [String: Any] = ["type": "sync", "playing": audio?.isPlaying ?? false]
        if let file = currentFile, let a = audio {
            out["file"] = file
            out["pos"] = position
            out["t"] = finite(a.currentTime)
            out["d"] = finite(a.duration)
        }
        return out
    }

    private func finite(_ v: Double) -> Double { return v.isFinite ? v : 0 }

    private func emitState() {
        onEvent?(["type": "state", "playing": audio?.isPlaying ?? false])
    }

    private func startTimer() {
        stopTimer()
        let t = Timer(timeInterval: 0.15, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        // The page only needs progress while it is on screen.
        guard let a = audio, UIApplication.shared.applicationState == .active else { return }
        a.updateMeters()
        let decibels = Double(a.averagePower(forChannel: 0))
        let level = max(0, min(1, pow(10, decibels / 20)))
        onEvent?(["type": "tick", "t": finite(a.currentTime), "d": finite(a.duration), "level": finite(level)])
    }

    // MARK: Lock screen and headphone controls

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] (_: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            self?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] (_: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] (_: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            self?.toggle()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] (_: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            self?.next(auto: false)
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] (_: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            self?.previous()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] (event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            if let e = event as? MPChangePlaybackPositionCommandEvent {
                self?.seek(e.positionTime)
            }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let file = currentFile, let a = audio else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        // "Artist - Title.mp3" shows as artist + title, like in the app.
        let name = Library.baseName(file)
        var title = name
        var artist = ""
        if let range = name.range(of: " - "), range.lowerBound > name.startIndex {
            artist = String(name[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
            title = String(name[range.upperBound...]).trimmingCharacters(in: .whitespaces)
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyPlaybackDuration: finite(a.duration),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: finite(a.currentTime),
            MPNowPlayingInfoPropertyPlaybackRate: a.isPlaying ? 1.0 : 0.0,
        ]
        if !artist.isEmpty { info[MPMediaItemPropertyArtist] = artist }
        if let art = artwork { info[MPMediaItemPropertyArtwork] = art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // Phone calls, alarms and so on pause the music; resume afterwards if it had been playing.
    @objc private func interrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let kind = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if kind == .began {
            stopTimer()
            emitState()
            updateNowPlaying()
        } else {
            let optionsRaw = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
            if wantsPlaying && shouldResume { play() }
        }
    }
}
