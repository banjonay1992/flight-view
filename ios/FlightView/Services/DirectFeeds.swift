import FlightViewCore
import Foundation

/// Feeds the phone queries itself, alongside the Worker.
///
/// adsb.fi and the OpenSky Network block cloud providers (including Cloudflare Workers) but
/// serve ordinary clients, so each phone asks them directly with the same coarse tile centre
/// the Worker sees. Each feed is paced well under its published limit and backs off on failure.
actor DirectFeeds {
    private struct Feed {
        let id: String
        let attribution: String
        let minInterval: TimeInterval
        let url: (Double, Double, Int) -> URL?
        let parse: (Data, Double) -> [Aircraft]
        var lastCall: Date = .distantPast
        var retryAt: Date = .distantPast
        var failures = 0
        var latest: [Aircraft] = []
        var latestAt: Date = .distantPast
    }

    private var feeds: [Feed] = [
        Feed(
            id: "adsbfi", attribution: "adsb.fi open data",
            minInterval: 2, // published limit is 1 request/second per client
            url: { lat, lon, r in
                URL(string: String(format: "https://opendata.adsb.fi/api/v2/lat/%.3f/lon/%.3f/dist/%d", lat, lon, min(250, Int(Double(r) / 1.852) + 1)))
            },
            parse: { data, now in FeedParsers.readsb(data, source: "adsbfi", fetchedAtS: now) }
        ),
        Feed(
            id: "opensky", attribution: "The OpenSky Network",
            minInterval: 20, // anonymous use is 400 credits a day per client
            url: { lat, lon, r in
                let dLat = Double(r) / 111.32
                let dLon = Double(r) / (111.32 * max(0.01, cos(lat * .pi / 180)))
                return URL(string: String(format: "https://opensky-network.org/api/states/all?lamin=%.3f&lomin=%.3f&lamax=%.3f&lomax=%.3f",
                                          lat - dLat, lon - dLon, lat + dLat, lon + dLon))
            },
            parse: { data, _ in FeedParsers.openSky(data, source: "opensky") }
        ),
    ]

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 4
        cfg.httpAdditionalHeaders = ["User-Agent": "FlightView/1.0 (iOS; plane-spotting app)", "Accept": "application/json"]
        return URLSession(configuration: cfg)
    }()

    /// Fresh results from each direct feed, and which feeds contributed.
    func poll(tileLat: Double, tileLon: Double, radiusKm: Int) async -> (lists: [[Aircraft]], attribution: [String]) {
        let now = Date()
        await withTaskGroup(of: (Int, [Aircraft]?, Int?).self) { group in
            for i in feeds.indices {
                let feed = feeds[i]
                guard now >= feed.retryAt, now.timeIntervalSince(feed.lastCall) >= feed.minInterval,
                      let url = feed.url(tileLat, tileLon, radiusKm) else { continue }
                feeds[i].lastCall = now
                let session = self.session
                let parse = feed.parse
                group.addTask {
                    do {
                        let (data, response) = try await session.data(from: url)
                        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                        guard code == 200 else { return (i, nil, code) }
                        return (i, parse(data, Date().timeIntervalSince1970), code)
                    } catch {
                        return (i, nil, nil)
                    }
                }
            }
            for await (i, result, code) in group {
                if let result {
                    feeds[i].latest = result
                    feeds[i].latestAt = Date()
                    feeds[i].failures = 0
                } else {
                    feeds[i].failures += 1
                    let backoff = code == 429 ? 300 : min(120, 5 * pow(2, Double(min(feeds[i].failures - 1, 5))))
                    feeds[i].retryAt = Date().addingTimeInterval(backoff)
                }
            }
        }
        let fresh = feeds.filter { !$0.latest.isEmpty && Date().timeIntervalSince($0.latestAt) < 60 }
        return (fresh.map(\.latest), fresh.map(\.attribution))
    }
}
