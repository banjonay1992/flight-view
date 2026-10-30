import SwiftUI

@MainActor
struct LogbookView: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var spots: [Spot] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView()
                } else if let error {
                    ContentUnavailableView("Couldn't load", systemImage: "wifi.exclamationmark", description: Text(error))
                } else if spots.isEmpty {
                    ContentUnavailableView("No spots yet", systemImage: "book.closed",
                                           description: Text("Tap a plane in the sky and save it here."))
                } else {
                    List {
                        ForEach(spots) { s in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.callsign ?? s.registration ?? s.icao24.uppercased()).font(.headline)
                                Text([s.typeName ?? s.typeCode, s.operator].compactMap { $0 }.joined(separator: " · "))
                                    .font(.subheadline).foregroundStyle(.secondary)
                                Text(s.spottedAt, format: .dateTime.day().month().year().hour().minute())
                                    .font(.caption).foregroundStyle(.tertiary)
                            }
                            .accessibilityElement(children: .combine)
                        }
                        .onDelete { idx in
                            let doomed = idx.map { spots[$0] }
                            spots.remove(atOffsets: idx)
                            Task { for s in doomed { try? await env.logbook.delete(s) } }
                        }
                    }
                }
            }
            .navigationTitle("Logbook")
            .toolbar { Button("Done") { dismiss() } }
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private func load() async {
        do {
            spots = try await env.logbook.recent()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}
