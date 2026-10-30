import FlightViewCore
import SwiftUI

/// Non-visual way to use the app: every aircraft in range, nearest first, with
/// clock-face directions relative to where the phone points. Fully VoiceOver friendly.
@MainActor
struct NearbyListView: View {
    let model: SkyViewModel
    /// Selection is applied by the parent after this sheet has fully dismissed (one sheet at a time).
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 2)) { _ in
                let items = model.nearby()
                let heading = model.cameraHeadingDeg
                Group {
                    if items.isEmpty {
                        ContentUnavailableView("No aircraft nearby", systemImage: "airplane",
                                               description: Text("Nothing airborne within 80 km right now."))
                    } else {
                        List(items) { m in
                            Button {
                                onPick(m.id)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(m.aircraft.displayName).font(.headline)
                                    Text(summary(m, heading: heading))
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .accessibilityHint("Shows details")
                        }
                    }
                }
            }
            .navigationTitle("Nearby")
            .toolbar { Button("Done") { dismiss() } }
        }
    }

    private func summary(_ m: SkyMarker, heading: Double?) -> String {
        let dir: String
        if let heading {
            dir = Fmt.clock(relativeDeg: m.look.azimuthDeg - heading)
        } else {
            dir = Fmt.compass(m.look.azimuthDeg)
        }
        let type = m.aircraft.typeName ?? m.aircraft.typeCode
        return [type, Fmt.distance(m.look.slantRangeM), dir, "\(Int(m.look.elevationDeg.rounded()))° up"]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}
