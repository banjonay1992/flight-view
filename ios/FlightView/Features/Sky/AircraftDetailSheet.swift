import FlightViewCore
import SwiftUI

@MainActor
struct AircraftDetailSheet: View {
    let model: SkyViewModel
    let aircraftID: String
    @State private var saved = false
    @State private var saving = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            if let m = model.snapshot(id: aircraftID) {
                content(m)
            } else {
                ContentUnavailableView("Out of view", systemImage: "airplane",
                                       description: Text("This aircraft has left the area."))
            }
        }
    }

    @ViewBuilder
    private func content(_ m: SkyMarker) -> some View {
        let a = m.aircraft
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(a.displayName).font(.largeTitle.bold())
                    if let op = a.operator { Text(op).font(.headline).foregroundStyle(.secondary) }
                    if let type = a.typeName ?? a.typeCode { Text(type).font(.subheadline) }
                }
                .accessibilityElement(children: .combine)
            }
            Section("Where it is") {
                Row("Distance", Fmt.distance(m.look.slantRangeM))
                Row("Altitude", Fmt.altitude(m.position.heightM))
                Row("Direction", "\(Fmt.compass(m.look.azimuthDeg)), \(Int(m.look.elevationDeg.rounded()))° up")
                if let gs = a.groundSpeedKt { Row("Speed", Fmt.speed(gs)) }
                if let trk = a.trackDeg { Row("Heading", Fmt.compass(trk)) }
            }
            Section("Aircraft") {
                if let r = a.registration { Row("Registration", r) }
                if let t = a.typeCode { Row("Type code", t) }
                Row("ICAO address", a.id.uppercased())
                if let sq = a.squawk { Row("Squawk", sq) }
                Row("Position age", "\(Int(m.positionAgeS.rounded())) s")
                if let s = a.sources, !s.isEmpty { Row("Seen by", s.joined(separator: ", ")) }
            }
            Section {
                Button {
                    saving = true
                    Task {
                        saved = await model.save(m)
                        saving = false
                    }
                } label: {
                    Label(saved ? "Saved to logbook" : "Save to logbook",
                          systemImage: saved ? "checkmark.circle.fill" : "plus.circle")
                }
                .disabled(saved || saving)
                .sensoryFeedback(.success, trigger: saved)
            }
        }
    }
}

private struct Row: View {
    let title: LocalizedStringKey
    let value: String
    init(_ title: LocalizedStringKey, _ value: String) {
        self.title = title
        self.value = value
    }
    var body: some View {
        LabeledContent(title, value: value)
    }
}
