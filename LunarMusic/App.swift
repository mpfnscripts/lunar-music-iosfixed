// Lunar Music for iPhone.
// The screen is the same glassy player page as the Windows version, shown in a web view.
// The app itself plays the music (Player.swift) so it keeps going with the screen locked.

import UIKit
import WebKit
import CryptoKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Library.ensureFolders()
        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: windowScene)
        w.rootViewController = PlayerViewController()
        w.makeKeyAndVisible()
        window = w
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        (window?.rootViewController as? PlayerViewController)?.syncPage()
    }
}

// MARK: - Music folder ("On My iPhone > Lunar Music" in the Files app)

enum Library {
    static let fm = FileManager.default

    static var docs: URL { fm.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var support: URL { fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] }
    static var customIcon: URL { support.appendingPathComponent("appicon.png") }

    static func ensureFolders() {
        for name in ["SONGS", "ICONS", "LYRICS"] {
            try? fm.createDirectory(at: docs.appendingPathComponent(name), withIntermediateDirectories: true)
        }
        try? fm.createDirectory(at: support, withIntermediateDirectories: true)
    }

    /// Every file with this extension anywhere in the app's folder, as a path relative to it.
    static func files(ext: String) -> [String] {
        let all = (try? fm.subpathsOfDirectory(atPath: docs.path)) ?? []
        let suffix = "." + ext
        return all.filter { path in
            path.lowercased().hasSuffix(suffix) && !path.split(separator: "/").contains(where: { $0.hasPrefix(".") })
        }
    }

    static func baseName(_ rel: String) -> String {
        let last = (rel as NSString).lastPathComponent
        return (last as NSString).deletingPathExtension
    }

    static func key(_ name: String) -> String {
        return name.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// Icon name (lowercased, no extension) -> relative path.
    static func iconMap() -> [String: String] {
        var map: [String: String] = [:]
        for path in files(ext: "png").sorted() {
            let k = key(baseName(path))
            if map[k] == nil { map[k] = path }
        }
        return map
    }

    static func iconPath(forSong rel: String) -> String? {
        let map = iconMap()
        return map[key(baseName(rel))] ?? map["default"]
    }

    static func songs() -> [[String: Any]] {
        let icons = iconMap()
        var out: [[String: Any]] = []
        for rel in files(ext: "mp3") {
            let name = baseName(rel)
            var folder = (rel as NSString).deletingLastPathComponent
            if folder.uppercased() == "SONGS" {
                folder = ""
            } else if folder.uppercased().hasPrefix("SONGS/") {
                folder = String(folder.dropFirst(6))
            }
            var icon: Any = NSNull()
            if let found = icons[key(name)] ?? icons["default"] { icon = found }
            out.append(["file": rel, "name": name, "folder": folder, "icon": icon])
        }
        out.sort { a, b in
            let x = (a["name"] as? String) ?? ""
            let y = (b["name"] as? String) ?? ""
            return x.localizedCaseInsensitiveCompare(y) == .orderedAscending
        }
        return out
    }

    /// Text of "<song name>.lrc" (or .txt), preferring one that sits beside the song.
    static func lyricsText(forSong rel: String) -> String? {
        let want = key(baseName(rel))
        let songDir = (rel as NSString).deletingLastPathComponent
        let candidates = (files(ext: "lrc").sorted() + files(ext: "txt").sorted()).filter { key(baseName($0)) == want }
        let beside = candidates.first(where: { ($0 as NSString).deletingLastPathComponent == songDir })
        guard let pick = beside ?? candidates.first,
              let data = try? Data(contentsOf: docs.appendingPathComponent(pick)), data.count <= 2_000_000 else { return nil }
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) {
            return String(data: data, encoding: .utf16)
        }
        return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
    }

    /// The tag block at the start of an MP3, where embedded lyrics live. The page reads it.
    static func id3Tag(_ url: URL) -> Data? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        let head = [UInt8](handle.readData(ofLength: 10))
        guard head.count == 10, head[0] == 0x49, head[1] == 0x44, head[2] == 0x33 else { return nil }
        let size = (Int(head[6] & 0x7f) << 21) | (Int(head[7] & 0x7f) << 14) | (Int(head[8] & 0x7f) << 7) | Int(head[9] & 0x7f)
        guard size > 0, size <= 32_000_000 else { return nil }
        var out = Data(head)
        out.append(handle.readData(ofLength: size))
        return out
    }

    /// A file inside the app's folder, or nil if the path tries to leave it.
    static func safeURL(_ rel: String?) -> URL? {
        guard let rel = rel, !rel.isEmpty, !rel.hasPrefix("/"),
              !rel.components(separatedBy: "/").contains("..") else { return nil }
        let url = docs.appendingPathComponent(rel)
        return fm.fileExists(atPath: url.path) ? url : nil
    }
}

// MARK: - Auto-update
//
// Almost all of Lunar Music lives in one page (app.html). The app ships with a copy, and also
// downloads the newest copy from the GitHub repository, so new features arrive without reinstalling.
// A page that needs newer app code than this build has (see "lunar-native" in app.html) is skipped.

enum PageStore {
    /// Raise this, and the matching number in app.html, whenever the page starts needing new Swift code.
    static let nativeLevel = 1
    static let remote = URL(string: "https://raw.githubusercontent.com/mpfnscripts/lunar-music-iosfixed/main/LunarMusic/app.html")

    enum Result { case none, updated, needsReinstall }

    static var cacheFile: URL { return Library.support.appendingPathComponent("page.html") }

    static func sha(_ data: Data) -> String {
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func bundled() -> Data? {
        guard let url = Bundle.main.url(forResource: "app", withExtension: "html") else { return nil }
        return try? Data(contentsOf: url)
    }

    /// The level of app code a page asks for, or nil if it does not look like a Lunar Music page.
    static func requiredLevel(_ data: Data) -> Int? {
        guard data.count > 20_000, let text = String(data: data, encoding: .utf8),
              text.contains("<title>Lunar Music</title>"),
              let marker = text.range(of: "name=\"lunar-native\" content=\"") else { return nil }
        return Int(text[marker.upperBound...].prefix(while: { $0.isNumber }))
    }

    /// The page to show: a downloaded one if it was fetched while this same build was installed,
    /// otherwise the copy that came inside the app (so reinstalling a newer IPA always wins).
    static func current() -> Data? {
        guard let shipped = bundled() else { return nil }
        if UserDefaults.standard.string(forKey: "pageForBuild") == sha(shipped),
           let cached = try? Data(contentsOf: cacheFile),
           let level = requiredLevel(cached), level <= nativeLevel {
            return cached
        }
        return shipped
    }

    static func check(_ done: @escaping (Result) -> Void) {
        guard let remote = remote else { done(.none); return }
        let request = URLRequest(url: remote, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        URLSession.shared.dataTask(with: request) { data, response, _ in
            var result = Result.none
            if let data = data, (response as? HTTPURLResponse)?.statusCode == 200,
               let level = requiredLevel(data), let shipped = bundled(), let showing = current(),
               sha(data) != sha(showing) {
                if level > nativeLevel {
                    result = .needsReinstall
                } else if (try? data.write(to: cacheFile, options: .atomic)) != nil {
                    UserDefaults.standard.set(sha(shipped), forKey: "pageForBuild")
                    result = .updated
                }
            }
            DispatchQueue.main.async { done(result) }
        }.resume()
    }
}

// MARK: - Serves the player page and its data to the web view (lunar://app/...)

final class AppSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            respond(urlSchemeTask, Data(), "text/plain", status: 400)
            return
        }
        let path = url.path.isEmpty ? "/" : url.path
        let file = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "f" })?.value

        switch path {
        case "/":
            if let data = PageStore.current() {
                respond(urlSchemeTask, data, "text/html; charset=utf-8")
            } else {
                respond(urlSchemeTask, Data("app.html is missing".utf8), "text/plain", status: 500)
            }
        case "/api/ping":
            respond(urlSchemeTask, Data("ok".utf8), "text/plain")
        case "/api/library":
            respondJSON(urlSchemeTask, [
                "songs": Library.songs(),
                "songsPath": "On My iPhone > Lunar Music > SONGS",
                "iconsPath": "On My iPhone > Lunar Music > ICONS",
                "songsFound": true,
            ])
        case "/api/settings":
            respondJSON(urlSchemeTask, Self.settings())
        case "/api/lyrics":
            var text: Any = NSNull()
            if let u = Library.safeURL(file), u.pathExtension.lowercased() == "mp3", let rel = file,
               let found = Library.lyricsText(forSong: rel) {
                text = found
            }
            respondJSON(urlSchemeTask, ["text": text])
        case "/id3":
            if let u = Library.safeURL(file), u.pathExtension.lowercased() == "mp3", let tag = Library.id3Tag(u) {
                respond(urlSchemeTask, tag, "application/octet-stream")
            } else {
                respond(urlSchemeTask, Data(), "application/octet-stream", status: 204)
            }
        case "/icon":
            if let u = Library.safeURL(file), u.pathExtension.lowercased() == "png", let data = try? Data(contentsOf: u) {
                respond(urlSchemeTask, data, "image/png")
            } else {
                respond(urlSchemeTask, Data(), "text/plain", status: 404)
            }
        case "/appicon.png":
            if let data = try? Data(contentsOf: Library.customIcon) {
                respond(urlSchemeTask, data, "image/png")
            } else {
                respond(urlSchemeTask, Data(), "text/plain", status: 404)
            }
        default:
            respond(urlSchemeTask, Data(), "text/plain", status: 404)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    static func settings() -> [String: Any] {
        let defaults = UserDefaults.standard
        var ui: Any = NSNull()
        if let saved = defaults.string(forKey: "ui"), let data = saved.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) {
            ui = obj
        }
        return [
            "songs": "", "icons": "", "lyrics": "", "ui": ui,
            "songsFound": true, "iconsFound": true,
            "customIcon": FileManager.default.fileExists(atPath: Library.customIcon.path),
            "iconVersion": defaults.integer(forKey: "iconVersion"),
        ]
    }

    private func respondJSON(_ task: WKURLSchemeTask, _ object: [String: Any]) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        respond(task, data, "application/json; charset=utf-8")
    }

    private func respond(_ task: WKURLSchemeTask, _ data: Data, _ mime: String, status: Int = 200) {
        guard let url = task.request.url else { return }
        let headers = ["Content-Type": mime, "Content-Length": String(data.count), "Cache-Control": "no-store"]
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }
}

// MARK: - The one screen

final class PlayerViewController: UIViewController, WKScriptMessageHandler {
    private var webView: WKWebView!
    private let player = Player()
    private var updateWaiting = false
    private var toldAboutReinstall = false
    private var lastUpdateCheck = Date.distantPast

    override var preferredStatusBarStyle: UIStatusBarStyle { return .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.03, green: 0.04, blue: 0.10, alpha: 1)

        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(AppSchemeHandler(), forURLScheme: "lunar")
        config.userContentController.add(self, name: "lunar")

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        view.addSubview(webView)

        player.onEvent = { [weak self] event in self?.send(event) }

        if let start = URL(string: "lunar://app/") {
            webView.load(URLRequest(url: start))
        }
    }

    /// Tell the page what is playing right now (used when the app comes back to the front).
    func syncPage() {
        guard isViewLoaded else { return }
        if updateWaiting && !player.isPlaying {
            updateWaiting = false
            webView.reload()
        } else {
            send(player.snapshot())
        }
        checkForUpdate()
    }

    /// Look for a newer player page every time the app is opened or brought back to the front
    /// (the short gap only stops double checks). It is applied straight away if nothing is
    /// playing, otherwise the next time the app is opened while the music is stopped.
    private func checkForUpdate() {
        guard Date().timeIntervalSince(lastUpdateCheck) > 15 else { return }
        lastUpdateCheck = Date()
        PageStore.check { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .updated:
                if self.player.isPlaying {
                    self.updateWaiting = true
                } else {
                    self.webView.reload()
                }
            case .needsReinstall:
                if !self.toldAboutReinstall {
                    self.toldAboutReinstall = true
                    self.send(["type": "toast", "text": "A bigger update is out. Install the newest IPA to get it."])
                }
            case .none:
                break
            }
        }
    }

    private func send(_ event: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: event),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript("window.lunarNative && window.lunarNative(\(json));", completionHandler: nil)
    }

    // Messages from the page: buttons the user tapped.
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let cmd = body["cmd"] as? String else { return }
        func int(_ key: String) -> Int { return (body[key] as? NSNumber)?.intValue ?? -1 }
        func double(_ key: String) -> Double { return (body[key] as? NSNumber)?.doubleValue ?? 0 }

        switch cmd {
        case "queue":
            player.setQueue((body["files"] as? [String]) ?? [], repeatMode: (body["repeat"] as? String) ?? "off")
        case "playIndex":
            player.load(position: int("pos"), autoplay: (body["autoplay"] as? Bool) ?? false)
        case "play":
            player.play()
        case "pause":
            player.pause()
        case "next":
            player.next(auto: false)
        case "prev":
            player.previous()
        case "seek":
            player.seek(double("t"))
        case "volume":
            player.setVolume(Float(double("v")))
        case "repeat":
            player.setRepeat((body["mode"] as? String) ?? "off")
        case "saveUI":
            if let ui = body["ui"] as? String { UserDefaults.standard.set(ui, forKey: "ui") }
        case "setIcon":
            if let b64 = body["data"] as? String, let data = Data(base64Encoded: b64),
               let image = UIImage(data: data), let png = image.pngData() {
                try? png.write(to: Library.customIcon)
                bumpIcon()
            }
        case "clearIcon":
            try? FileManager.default.removeItem(at: Library.customIcon)
            bumpIcon()
        default:
            break
        }
    }

    private func bumpIcon() {
        let defaults = UserDefaults.standard
        defaults.set(defaults.integer(forKey: "iconVersion") + 1, forKey: "iconVersion")
        send(["type": "settings"])
    }
}
