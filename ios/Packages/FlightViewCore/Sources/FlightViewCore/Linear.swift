import Foundation

/// Minimal 3-vector. Mirrors `android/core/.../Linear.kt`.
public struct Vec3: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double

    public init(_ x: Double, _ y: Double, _ z: Double) {
        self.x = x
        self.y = y
        self.z = z
    }

    public static func + (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }
    public static func - (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x - b.x, a.y - b.y, a.z - b.z) }
    public static func * (a: Vec3, s: Double) -> Vec3 { Vec3(a.x * s, a.y * s, a.z * s) }
    public func dot(_ o: Vec3) -> Double { x * o.x + y * o.y + z * o.z }
    public var length: Double { dot(self).squareRoot() }
    public var normalized: Vec3 { let l = length; return l == 0 ? self : self * (1 / l) }
}

/// Row-major 3x3 matrix. Device attitude `R_dw` maps device coordinates to ENU: `w = R_dw * d`.
public struct Mat3: Equatable, Sendable {
    public private(set) var m: [Double]

    public init(_ values: [Double]) {
        precondition(values.count == 9, "Mat3 needs 9 values")
        m = values
    }

    public init(rows r0: [Double], _ r1: [Double], _ r2: [Double]) {
        self.init(r0 + r1 + r2)
    }

    public static let identity = Mat3([1, 0, 0, 0, 1, 0, 0, 0, 1])

    public subscript(row: Int, col: Int) -> Double { m[row * 3 + col] }

    public static func * (a: Mat3, v: Vec3) -> Vec3 {
        let m = a.m
        return Vec3(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z
        )
    }

    public static func * (a: Mat3, b: Mat3) -> Mat3 {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 {
            for j in 0..<3 {
                var s = 0.0
                for k in 0..<3 { s += a[i, k] * b[k, j] }
                r[i * 3 + j] = s
            }
        }
        return Mat3(r)
    }

    public var transposed: Mat3 {
        Mat3([m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]])
    }

    /// `R^T * v` without allocating the transpose.
    public func transposeTimes(_ v: Vec3) -> Vec3 {
        Vec3(
            m[0] * v.x + m[3] * v.y + m[6] * v.z,
            m[1] * v.x + m[4] * v.y + m[7] * v.z,
            m[2] * v.x + m[5] * v.y + m[8] * v.z
        )
    }
}
