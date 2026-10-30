// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "FlightViewCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "FlightViewCore", targets: ["FlightViewCore"]),
    ],
    targets: [
        // Pure Swift, Foundation only: no simd, so it builds and tests on macOS and Linux.
        .target(name: "FlightViewCore"),
        .testTarget(name: "FlightViewCoreTests", dependencies: ["FlightViewCore"]),
    ]
)
