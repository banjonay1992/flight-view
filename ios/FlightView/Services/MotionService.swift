import CoreMotion
import FlightViewCore
import Observation
import os

/// Device attitude relative to true north, at display rate.
///
/// The latest attitude is held behind a lock and read by the render loop each frame,
/// rather than pushed through SwiftUI at 60 Hz. Only slow-changing state
/// (calibration quality, availability) is observable.
@Observable
final class MotionService: @unchecked Sendable {
    enum Calibration: Equatable { case unknown, poor, ok, good }

    /// Written only on the main actor (see `start`).
    private(set) var calibration: Calibration = .unknown
    private(set) var isAvailable = true

    @ObservationIgnored private let manager = CMMotionManager()
    @ObservationIgnored private let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "FlightView.motion"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .userInteractive
        return q
    }()

    @ObservationIgnored private let latest = OSAllocatedUnfairLock<Mat3?>(initialState: nil)
    @ObservationIgnored private var resolver = AttitudeResolver() // only touched on `queue`

    /// ENU (true north) device attitude, or nil until the first confident sample.
    var attitude: Mat3? { latest.withLock { $0 } }

    @MainActor
    func start() {
        guard manager.isDeviceMotionAvailable,
              CMMotionManager.availableAttitudeReferenceFrames().contains(.xTrueNorthZVertical) else {
            isAvailable = false
            return
        }
        manager.deviceMotionUpdateInterval = 1.0 / 60.0
        manager.showsDeviceMovementDisplay = true // lets iOS show its figure-of-eight calibration prompt
        manager.startDeviceMotionUpdates(using: .xTrueNorthZVertical, to: queue) { @Sendable [weak self] motion, _ in
            guard let self, let motion else { return }
            let r = motion.attitude.rotationMatrix
            let rows = Mat3([r.m11, r.m12, r.m13, r.m21, r.m22, r.m23, r.m31, r.m32, r.m33])
            let g = Vec3(motion.gravity.x, motion.gravity.y, motion.gravity.z)
            if let enu = self.resolver.resolve(rows: rows, gravity: g) {
                self.latest.withLock { $0 = enu }
            }
            let quality: Calibration = switch motion.magneticField.accuracy {
            case .uncalibrated: .unknown
            case .low: .poor
            case .medium: .ok
            case .high: .good
            @unknown default: .unknown
            }
            Task { @MainActor in
                if self.calibration != quality { self.calibration = quality }
            }
        }
    }

    @MainActor
    func stop() {
        manager.stopDeviceMotionUpdates()
    }
}
