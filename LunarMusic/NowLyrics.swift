// Lyrics on the lock screen and in CarPlay.
// When the setting is on, the line being sung is shown in place of the artist in the system's
// Now Playing info, which is what the lock screen and CarPlay's Now Playing screen display.
// The timing runs here in the app, because the player page is paused while the phone is locked.

import Foundation

final class LyricLines {
    var enabled = false
    var offsets: [String: Double] = [:]          // per-song "earlier / later" nudges from the page
    private var cache: [String: [(Double, String)]] = [:]
    private var order: [String] = []

    /// Timed lines sent by the page (these include lyrics stored inside the MP3).
    func store(file: String, lines: [(Double, String)]) {
        if cache[file] == nil { order.append(file) }
        cache[file] = lines
        while order.count > 60 { cache[order.removeFirst()] = nil }
    }

    /// Timed lines for a song: what the page sent, or else the song's .lrc file read directly,
    /// so songs that start while the phone is locked still get lyrics.
    func lines(for file: String) -> [(Double, String)] {
        if let known = cache[file] { return known }
        var found: [(Double, String)] = []
        if let text = Library.lyricsText(forSong: file), let parsed = LyricLines.parse(text) { found = parsed }
        store(file: file, lines: found)
        return found
    }

    /// Reads LRC text: "[01:23.45]words". Needs at least two timed lines.
    static func parse(_ text: String) -> [(Double, String)]? {
        var out: [(Double, String)] = []
        var offset = 0.0
        let clean = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        for raw in clean.components(separatedBy: "\n") {
            var rest = Substring(raw)
            var times: [Double] = []
            while true {
                let trimmed = rest.drop(while: { $0 == " " || $0 == "\t" || $0 == "\u{FEFF}" })
                guard trimmed.first == "[", let close = trimmed.firstIndex(of: "]") else { break }
                let inside = trimmed[trimmed.index(after: trimmed.startIndex)..<close]
                if let t = stamp(inside) {
                    times.append(t)
                    rest = trimmed[trimmed.index(after: close)...]
                } else {
                    if times.isEmpty, inside.lowercased().hasPrefix("offset:"),
                       let ms = Double(inside.dropFirst(7).trimmingCharacters(in: .whitespaces)) {
                        offset = ms / 1000
                    }
                    break
                }
            }
            if times.isEmpty { continue }
            let words = String(rest)
                .replacingOccurrences(of: "<[0-9:.]+>", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            for t in times { out.append((t, words)) }
        }
        guard out.count >= 2 else { return nil }
        return out.map { (max(0, $0.0 - offset), $0.1) }.sorted { $0.0 < $1.0 }
    }

    /// "mm:ss", "mm:ss.xx" or "mm:ss:xx" -> seconds.
    private static func stamp(_ s: Substring) -> Double? {
        let parts = s.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[0].allSatisfy({ $0.isNumber }), let minutes = Int(parts[0]) else { return nil }
        let secText = parts[1].replacingOccurrences(of: ":", with: ".")
        guard let first = secText.first, first.isNumber, let seconds = Double(secText) else { return nil }
        return Double(minutes) * 60 + seconds
    }
}
