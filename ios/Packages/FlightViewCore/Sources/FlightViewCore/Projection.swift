import Foundation

/// Pinhole intrinsics in view points.
public struct Intrinsics: Equatable, Sendable {
    public var fx: Double, fy: Double, cx: Double, cy: Double

    public init(fx: Double, fy: Double, cx: Double, cy: Double) {
        self.fx = fx
        self.fy = fy
        self.cx = cx
        self.cy = cy
    }

    /// Intrinsics for a portrait preview that aspect-fills its view.
    /// - Parameters:
    ///   - longSideFovDeg: FOV along the sensor's long side (`AVCaptureDevice.Format.videoFieldOfView`).
    ///   - imageAspect: long side / short side of the stream, e.g. 4/3.
    public static func aspectFill(longSideFovDeg: Double, imageAspect: Double, viewW: Double, viewH: Double) -> Intrinsics {
        let fImg = (imageAspect / 2) / tan(Geodesy.rad(longSideFovDeg) / 2)
        let scale = max(viewW / 1, viewH / imageAspect)
        let f = fImg * scale
        return Intrinsics(fx: f, fy: f, cx: viewW / 2, cy: viewH / 2)
    }
}

public struct ScreenPoint: Equatable, Sendable {
    public var x: Double, y: Double, depth: Double
    public var inFront: Bool, onScreen: Bool
}

public enum Projection {
    /// Project a unit ENU direction onto the screen. Device frame: +X right, +Y up, +Z towards the user;
    /// the rear camera looks along -Z.
    public static func project(_ rDw: Mat3, worldDir: Vec3, k: Intrinsics, viewW: Double, viewH: Double) -> ScreenPoint {
        let d = rDw.transposeTimes(worldDir)
        let zc = -d.z
        guard zc > 1e-6 else {
            return ScreenPoint(x: .nan, y: .nan, depth: zc, inFront: false, onScreen: false)
        }
        let x = k.cx + k.fx * d.x / zc
        let y = k.cy - k.fy * d.y / zc
        let on = (0...viewW).contains(x) && (0...viewH).contains(y)
        return ScreenPoint(x: x, y: y, depth: zc, inFront: true, onScreen: on)
    }

    /// Screen direction to turn towards a target (0 = right, +pi/2 = up).
    public static func edgeAngleRad(_ rDw: Mat3, worldDir: Vec3) -> Double {
        let d = rDw.transposeTimes(worldDir)
        return atan2(d.y, d.x)
    }

    /// Heading correction, degrees clockwise seen from above (manual nudge, or declination if ever needed).
    public static func rotateAboutUp(_ rDw: Mat3, degreesClockwise: Double) -> Mat3 {
        let d = Geodesy.rad(degreesClockwise)
        let c = cos(d), s = sin(d)
        return Mat3([c, s, 0, -s, c, 0, 0, 0, 1]) * rDw
    }

    /// CoreMotion `.xTrueNorthZVertical` is (X north, Y west, Z up). Convert a device->reference matrix to ENU.
    public static func referenceNwuToEnu(_ rDeviceToRef: Mat3) -> Mat3 {
        Mat3([0, -1, 0, 1, 0, 0, 0, 0, 1]) * rDeviceToRef
    }
}
