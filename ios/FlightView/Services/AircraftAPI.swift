import FlightViewCore
import Foundation

enum AircraftAPIError: LocalizedError {
    case busy
    case server(Int)
    case unauthorised

    var errorDescription: String? {
        switch self {
        case .busy: String(localized: "Live feeds are busy. Retrying…")
        case .server(let code): String(localized: "The flight service returned an error (\(code)).")
        case .unauthorised: String(localized: "Couldn't sign in to the flight service.")
        }
    }
}

/// Talks to the Flight View Worker. The precise location never leaves the device:
/// it is snapped to the same 0.1 degree tile the server uses before the request is built.
actor AircraftAPI {
    private let baseURL: URL
    private let session: SessionStore
    private let urlSession: URLSession
    private let decoder = JSONDecoder()

    /// serverTime - deviceTime, measured on each response. Lets dead reckoning ignore a wrong phone clock.
    private(set) var clockOffsetS: Double = 0

    init(baseURL: URL, session: SessionStore) {
        self.baseURL = baseURL
        self.session = session
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 8
        cfg.waitsForConnectivity = false
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        urlSession = URLSession(configuration: cfg)
    }

    static let tileDeg = 0.1

    static func tileCentre(latDeg: Double, lonDeg: Double) -> (lat: Double, lon: Double) {
        let lat = ((latDeg / tileDeg).rounded(.down) + 0.5) * tileDeg
        let lon = ((lonDeg / tileDeg).rounded(.down) + 0.5) * tileDeg
        return ((lat * 100).rounded() / 100, (lon * 100).rounded() / 100)
    }

    func fetch(latDeg: Double, lonDeg: Double, radiusKm: Double = 80) async throws -> AircraftResponse {
        let tile = Self.tileCentre(latDeg: latDeg, lonDeg: lonDeg)
        var comps = URLComponents(url: baseURL.appending(path: "v1/aircraft"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "lat", value: String(format: "%.2f", tile.lat)),
            URLQueryItem(name: "lon", value: String(format: "%.2f", tile.lon)),
            URLQueryItem(name: "radius_km", value: String(Int(radiusKm))),
        ]
        var request = URLRequest(url: comps.url!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let token = try await session.accessToken()
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let sentAt = Date().timeIntervalSince1970
        let (data, response) = try await urlSession.data(for: request)
        let receivedAt = Date().timeIntervalSince1970
        guard let http = response as? HTTPURLResponse else { throw AircraftAPIError.server(-1) }
        switch http.statusCode {
        case 200: break
        case 401: throw AircraftAPIError.unauthorised
        case 429, 503: throw AircraftAPIError.busy
        default: throw AircraftAPIError.server(http.statusCode)
        }
        let body = try decoder.decode(AircraftResponse.self, from: data)
        // Assume the server stamped the response halfway through the round trip.
        let measured = body.serverTime - (sentAt + receivedAt) / 2
        clockOffsetS = clockOffsetS == 0 ? measured : clockOffsetS * 0.8 + measured * 0.2
        return body
    }
}
