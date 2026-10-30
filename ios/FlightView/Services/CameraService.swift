@preconcurrency import AVFoundation
import Observation
import UIKit

/// Rear wide camera preview. Exposes the field of view and stream aspect so the overlay's
/// projection matches exactly what the preview layer draws (aspect-fill, portrait).
@Observable
@MainActor
final class CameraService {
    enum State: Equatable { case idle, running, denied, unavailable }

    private(set) var state: State = .idle
    /// Horizontal FOV of the active format in its native landscape orientation (the long side).
    private(set) var longSideFovDeg: Double = 65
    /// Long side / short side of the stream.
    private(set) var imageAspect: Double = 4.0 / 3.0

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "FlightView.camera")
    private var configured = false

    func start() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .video) else { state = .denied; return }
        default:
            state = .denied
            return
        }
        if !configured {
            guard let info = configure() else { state = .unavailable; return }
            longSideFovDeg = info.fov
            imageAspect = info.aspect
            configured = true
        }
        let session = self.session
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            sessionQueue.async {
                if !session.isRunning { session.startRunning() }
                done.resume()
            }
        }
        state = .running
    }

    func stop() {
        let session = self.session
        sessionQueue.async { if session.isRunning { session.stopRunning() } }
        if state == .running { state = .idle }
    }

    private func configure() -> (fov: Double, aspect: Double)? {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else { return nil }
        session.beginConfiguration()
        session.sessionPreset = .high
        guard session.canAddInput(input) else {
            session.commitConfiguration()
            return nil
        }
        session.addInput(input)
        // Commit first: the preset only picks the active format once committed, and the
        // projection must use that format's FOV and dimensions.
        session.commitConfiguration()

        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = 1 // the projection assumes no zoom
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            device.unlockForConfiguration()
        } catch {
            // Non-fatal: defaults are fine.
        }
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let long = Double(max(dims.width, dims.height)), short = Double(min(dims.width, dims.height))
        return (Double(device.activeFormat.videoFieldOfView), short > 0 ? long / short : 4.0 / 3.0)
    }
}

/// UIKit bridge for the preview layer. Always portrait, always aspect-fill.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill // portrait by default; the app is portrait-only
        v.isAccessibilityElement = false
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
