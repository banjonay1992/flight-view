import CoreLocation
import FlightViewCore
import Observation

/// Observer position. Uses ellipsoidal altitude so heights share the WGS84 frame with
/// ADS-B geometric altitude (mixing in mean-sea-level height would add a ~50 m geoid error).
@Observable
@MainActor
final class LocationService: NSObject, CLLocationManagerDelegate {
    private(set) var observer: GeoPoint?
    private(set) var horizontalAccuracyM: Double?
    private(set) var authorization: CLAuthorizationStatus

    private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        manager.activityType = .other
    }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    func start() {
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            if status == .authorizedWhenInUse || status == .authorizedAlways { self.start() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy >= 0 else { return }
        let height = loc.verticalAccuracy >= 0 ? loc.ellipsoidalAltitude : 0
        let point = GeoPoint(latDeg: loc.coordinate.latitude, lonDeg: loc.coordinate.longitude, heightM: height)
        let accuracy = loc.horizontalAccuracy
        Task { @MainActor in
            self.observer = point
            self.horizontalAccuracyM = accuracy
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient failures are common (e.g. indoors); keep the last fix and carry on.
    }
}
