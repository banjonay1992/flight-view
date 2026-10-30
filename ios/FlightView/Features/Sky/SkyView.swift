import FlightViewCore
import SwiftUI

@MainActor
struct SkyView: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model: SkyViewModel?
    @State private var showList = false
    @State private var showLogbook = false
    @State private var calibrating = false
    @State private var dragStartNudge: Double = 0
    @State private var pendingSelection: String?

    var body: some View {
        ZStack {
            CameraPreview(session: env.camera.session).ignoresSafeArea()
            if let model {
                GeometryReader { geo in
                    TimelineView(.animation(minimumInterval: reduceMotion ? 1.0 / 15 : nil)) { _ in
                        let frame = model.frame(size: geo.size)
                        overlay(model: model, frame: frame, size: geo.size)
                    }
                }
                .ignoresSafeArea()
                chrome(model: model)
            }
        }
        .task {
            if model == nil { model = SkyViewModel(env: env) }
            model?.start()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model?.start() } else { model?.stop() }
        }
        .sheet(isPresented: Binding(get: { model?.selectedID != nil }, set: { if !$0 { model?.selectedID = nil } })) {
            if let model, let id = model.selectedID {
                AircraftDetailSheet(model: model, aircraftID: id)
                    .presentationDetents([.medium, .large])
                    .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            }
        }
        .sheet(isPresented: $showList, onDismiss: {
            if let id = pendingSelection { model?.selectedID = id }
            pendingSelection = nil
        }) {
            if let model { NearbyListView(model: model) { pendingSelection = $0 } }
        }
        .sheet(isPresented: $showLogbook) {
            LogbookView()
        }
    }

    // MARK: Overlay

    @ViewBuilder
    private func overlay(model: SkyViewModel, frame: SkyViewModel.Frame?, size: CGSize) -> some View {
        if let frame {
            ZStack(alignment: .topLeading) {
                Canvas { ctx, canvasSize in
                    for m in frame.markers {
                        if let p = frame.smoothed[m.id] {
                            let r: CGFloat = m.id == model.selectedID ? 9 : 6
                            let rect = CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)
                            ctx.stroke(Path(ellipseIn: rect), with: .color(.white.opacity(m.isStale ? 0.4 : 0.95)), lineWidth: 2)
                            ctx.fill(Path(ellipseIn: rect.insetBy(dx: 3, dy: 3)), with: .color(Theme.accent.opacity(m.isStale ? 0.4 : 1)))
                        } else if m.look.slantRangeM < 40_000 {
                            drawEdgeArrow(ctx: ctx, size: canvasSize, angle: m.edgeAngleRad)
                        }
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)

                ForEach(frame.labels, id: \.id) { label in
                    if let m = frame.markers.first(where: { $0.id == label.id }) {
                        MarkerLabel(marker: m)
                            .frame(width: label.width, height: label.height)
                            .position(x: label.left + label.width / 2, y: label.top + label.height / 2)
                            .allowsHitTesting(false)
                    }
                }
            }
            .frame(width: size.width, height: size.height)
            .contentShape(Rectangle())
            .gesture(SpatialTapGesture().onEnded { model.select(at: $0.location, in: frame) },
                     including: calibrating ? .none : .all)
            .simultaneousGesture(calibrationDrag(model: model, width: size.width),
                                 including: calibrating ? .all : .none)
        } else {
            WaitingView(message: waitingMessage)
        }
    }

    private var waitingMessage: String {
        if env.location.observer == nil { return String(localized: "Finding your location…") }
        if !env.motion.isAvailable { return String(localized: "This device has no compass or motion sensors.") }
        return String(localized: "Hold your phone up to the sky")
    }

    private func drawEdgeArrow(ctx: GraphicsContext, size: CGSize, angle: Double) {
        let cx = size.width / 2, cy = size.height / 2
        let dx = cos(angle), dy = -sin(angle)
        let inset: CGFloat = 28
        let sx = abs(dx) < 1e-6 ? .infinity : (cx - inset) / abs(dx)
        let sy = abs(dy) < 1e-6 ? .infinity : (cy - inset) / abs(dy)
        let s = min(sx, sy)
        let tip = CGPoint(x: cx + dx * s, y: cy + dy * s)
        var path = Path()
        let back = CGPoint(x: tip.x - dx * 14, y: tip.y - dy * 14)
        let nx = -dy * 7, ny = dx * 7
        path.move(to: tip)
        path.addLine(to: CGPoint(x: back.x + nx, y: back.y + ny))
        path.addLine(to: CGPoint(x: back.x - nx, y: back.y - ny))
        path.closeSubpath()
        ctx.fill(path, with: .color(.white.opacity(0.7)))
    }

    private func calibrationDrag(model: SkyViewModel, width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                if v.translation == .zero { dragStartNudge = model.headingNudgeDeg }
                // Dragging the sky right moves labels right: ~ 30 degrees per screen width.
                let deg = dragStartNudge - Double(v.translation.width / width) * 30
                model.headingNudgeDeg = max(-45, min(45, deg))
            }
    }

    // MARK: Chrome

    private func chrome(model: SkyViewModel) -> some View {
        VStack(spacing: 12) {
            HStack(alignment: .top) {
                if let msg = model.statusMessage {
                    StatusPill(text: msg, systemImage: "exclamationmark.triangle")
                } else if env.motion.calibration == .poor {
                    StatusPill(text: String(localized: "Compass needs calibrating: move your phone in a figure of eight"),
                               systemImage: "location.north.line")
                }
                Spacer()
            }
            Spacer()
            if calibrating {
                CalibrationBar(nudge: model.headingNudgeDeg,
                               reset: { model.headingNudgeDeg = 0 },
                               done: { calibrating = false })
            }
            HStack(spacing: 12) {
                RoundButton(systemImage: "list.bullet", label: String(localized: "Nearby aircraft list")) { showList = true }
                RoundButton(systemImage: "scope", label: String(localized: "Align compass")) { calibrating.toggle() }
                RoundButton(systemImage: "book.closed", label: String(localized: "Logbook")) { showLogbook = true }
            }
            if !model.attribution.isEmpty {
                Text("Data: \(model.attribution.joined(separator: " · "))")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
        }
        .padding()
    }
}

private struct MarkerLabel: View {
    let marker: SkyMarker

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(marker.aircraft.displayName)
                .font(.system(.subheadline, design: .rounded).weight(.semibold))
            Text("\(Fmt.altitude(marker.position.heightM)) · \(Fmt.distance(marker.look.slantRangeM))")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .opacity(marker.isStale ? 0.55 : 1)
    }
}

private struct WaitingView: View {
    let message: String
    var body: some View {
        VStack { Spacer(); StatusPill(text: message, systemImage: "airplane"); Spacer() }
            .frame(maxWidth: .infinity)
    }
}

struct StatusPill: View {
    let text: String
    let systemImage: String
    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
    }
}

private struct RoundButton: View {
    let systemImage: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.semibold))
                .frame(width: 52, height: 52)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct CalibrationBar: View {
    let nudge: Double
    let reset: () -> Void
    let done: () -> Void
    var body: some View {
        VStack(spacing: 8) {
            Text("Point at a plane you can see, then drag left or right until its label sits on it.")
                .font(.footnote)
                .multilineTextAlignment(.center)
            HStack {
                Text("Offset \(nudge, format: .number.precision(.fractionLength(1)))°").monospacedDigit()
                Spacer()
                Button("Reset", action: reset)
                Button("Done", action: done).bold()
            }
        }
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
