// Helper jobs for syncing with Google Drive.
// The sync itself is run by the player page (app.html). The page cannot touch files or the
// network, so it asks the app to do a few simple things: list the music files, make a web
// request to Google, download one file, delete one synced file, open Google's sign-in page.

import UIKit

final class HostBridge {
    private let session = URLSession(configuration: .default)
    private let fm = FileManager.default

    /// The page may only reach Google's sign-in and Drive servers.
    private func allowed(_ raw: String?) -> URL? {
        guard let raw = raw, let url = URL(string: raw), url.scheme == "https", let host = url.host,
              host == "oauth2.googleapis.com" || host == "www.googleapis.com" else { return nil }
        return url
    }

    private func folder(_ root: String?) -> URL? {
        switch root {
        case "songs": return Library.docs.appendingPathComponent("SONGS", isDirectory: true)
        case "icons": return Library.docs.appendingPathComponent("ICONS", isDirectory: true)
        case "lyrics": return Library.docs.appendingPathComponent("LYRICS", isDirectory: true)
        default: return nil
        }
    }

    private func extensions(_ root: String?) -> [String] {
        switch root {
        case "songs": return ["mp3"]
        case "icons": return ["png"]
        case "lyrics": return ["lrc", "txt"]
        default: return []
        }
    }

    /// A file inside one of the three music folders, of the right type; nil for anything else.
    private func localURL(root: String?, file: String?) -> URL? {
        guard let dir = folder(root), let rel = file, !rel.isEmpty, !rel.hasPrefix("/"),
              !rel.components(separatedBy: "/").contains(".."),
              extensions(root).contains((rel as NSString).pathExtension.lowercased()) else { return nil }
        return dir.appendingPathComponent(rel)
    }

    private func request(_ m: [String: Any]) -> URLRequest? {
        guard let url = allowed(m["url"] as? String) else { return nil }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 120)
        req.httpMethod = (m["method"] as? String) ?? "GET"
        if let headers = m["headers"] as? [String: String] {
            for (name, value) in headers { req.setValue(value, forHTTPHeaderField: name) }
        }
        return req
    }

    private func listFiles() -> [String: Any] {
        var out: [String: Any] = [:]
        for root in ["songs", "icons", "lyrics"] {
            var files: [[String: Any]] = []
            var found = false
            if let dir = folder(root) {
                var isDir: ObjCBool = false
                found = fm.fileExists(atPath: dir.path, isDirectory: &isDir) && isDir.boolValue
                let exts = extensions(root)
                for rel in (try? fm.subpathsOfDirectory(atPath: dir.path)) ?? [] {
                    if rel.split(separator: "/").contains(where: { $0.hasPrefix(".") }) { continue }
                    if !exts.contains((rel as NSString).pathExtension.lowercased()) { continue }
                    let attrs = try? fm.attributesOfItem(atPath: dir.appendingPathComponent(rel).path)
                    let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
                    files.append(["file": rel, "size": size])
                }
            }
            out[root] = ["found": found, "files": files] as [String: Any]
        }
        return out
    }

    /// Runs one job and calls `reply(ok, resultOrErrorText)` on the main thread.
    func handle(_ m: [String: Any], reply: @escaping (Bool, Any) -> Void) {
        let op = (m["op"] as? String) ?? ""
        switch op {
        case "files":
            reply(true, listFiles())

        case "open":
            if (m["url"] as? String) == "https://www.google.com/device", let url = URL(string: "https://www.google.com/device") {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
                reply(true, [String: Any]())
            } else {
                reply(false, "not allowed")
            }

        case "delete":
            guard let url = localURL(root: m["root"] as? String, file: m["file"] as? String) else {
                reply(false, "bad file")
                return
            }
            try? fm.removeItem(at: url)
            reply(true, [String: Any]())

        case "http":
            guard var req = request(m) else {
                reply(false, "address not allowed")
                return
            }
            if let body = m["body"] as? String, !body.isEmpty { req.httpBody = body.data(using: .utf8) }
            session.dataTask(with: req) { data, response, error in
                DispatchQueue.main.async {
                    guard let http = response as? HTTPURLResponse else {
                        reply(false, "no connection: " + (error?.localizedDescription ?? "no answer"))
                        return
                    }
                    let text = String(data: data ?? Data(), encoding: .utf8) ?? ""
                    let location = http.value(forHTTPHeaderField: "Location") ?? ""
                    reply(true, ["status": http.statusCode, "body": text, "location": location] as [String: Any])
                }
            }.resume()

        case "download":
            guard let dest = localURL(root: m["root"] as? String, file: m["file"] as? String) else {
                reply(false, "bad file")
                return
            }
            guard let req = request(m) else {
                reply(false, "address not allowed")
                return
            }
            let files = fm
            session.downloadTask(with: req) { temp, response, error in
                // The temporary file disappears when this block returns, so move it right here.
                var ok = false
                var result: Any = "no connection: " + (error?.localizedDescription ?? "no answer")
                if let http = response as? HTTPURLResponse, let temp = temp {
                    if http.statusCode == 200 {
                        do {
                            try files.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                            if files.fileExists(atPath: dest.path) { try files.removeItem(at: dest) }
                            try files.moveItem(at: temp, to: dest)
                            let attrs = try? files.attributesOfItem(atPath: dest.path)
                            let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
                            ok = true
                            result = ["status": 200, "size": size] as [String: Any]
                        } catch {
                            result = "cannot save the file"
                        }
                    } else {
                        ok = true
                        result = ["status": http.statusCode, "body": ""] as [String: Any]
                    }
                }
                DispatchQueue.main.async { reply(ok, result) }
            }.resume()

        default:
            reply(false, "unknown request")   // sending files up is the PC's job
        }
    }
}
