import Foundation

/// Turns a CoreMotion rotation matrix into the ENU attitude the projection expects.
///
/// `CMAttitude.rotationMatrix` is documented loosely enough that it is easy to
/// get the direction (device->reference vs reference->device) backwards, and a
/// wrong guess silently mirrors every label. Instead of trusting a convention
/// we check it against gravity, which CoreMotion reports in device coordinates:
/// the correct matrix must map "up in the device frame" (minus gravity) onto
/// reference +Z. The decision is made once with a confident margin, then cached.
public struct AttitudeResolver: Sendable {
    public enum Orientation: Sendable { case asGiven, transposed }
    public private(set) var orientation: Orientation?

    public init() {}

    /// - Parameters:
    ///   - rows: CMRotationMatrix as rows (m11 m12 m13 / m21 ... / m31 ...).
    ///   - gravity: `CMDeviceMotion.gravity` in g, device frame.
    /// - Returns: `R_dw` in ENU (true north), or nil until gravity is usable.
    public mutating func resolve(rows m: Mat3, gravity: Vec3) -> Mat3? {
        if orientation == nil {
            let g = gravity.length
            guard g > 0.5 else { return nil } // free fall or no data yet
            let up = (gravity * -1).normalized
            let zAsGiven = (m * up).z
            let zTransposed = (m.transposed * up).z
            guard abs(zAsGiven - zTransposed) > 0.2 else { return nil } // ambiguous pose, wait a frame
            orientation = zAsGiven > zTransposed ? .asGiven : .transposed
        }
        let deviceToRef = orientation == .asGiven ? m : m.transposed
        return Projection.referenceNwuToEnu(deviceToRef)
    }
}
