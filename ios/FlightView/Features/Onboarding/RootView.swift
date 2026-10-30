import AVFoundation
import CoreLocation
import SwiftUI

/// Explains why we need the camera and location before iOS asks, then hands over to the sky view.
@MainActor
struct RootView: View {
    @Environment(AppEnvironment.self) private var env
    @AppStorage("onboarded") private var onboarded = false

    var body: some View {
        if onboarded && locationAllowed {
            SkyView()
        } else {
            OnboardingView(locationDenied: locationDenied) {
                env.location.requestPermission()
                Task {
                    _ = await AVCaptureDevice.requestAccess(for: .video)
                    onboarded = true
                }
            }
        }
    }

    private var locationAllowed: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(env.location.authorization)
    }

    private var locationDenied: Bool {
        [.denied, .restricted].contains(env.location.authorization)
    }
}

private struct OnboardingView: View {
    let locationDenied: Bool
    let onContinue: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            Spacer()
            Image(systemName: "airplane.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(Theme.accent)
                .accessibilityHidden(true)
            Text("Point your phone at a plane.\nSee what it is.")
                .font(.largeTitle.bold())
            VStack(alignment: .leading, spacing: 16) {
                Reason(icon: "camera", title: "Camera",
                       text: "Shows the sky with labels on top. Video never leaves your phone.")
                Reason(icon: "location", title: "Location",
                       text: "Works out which aircraft are around you. Only a rough area (about 10 km) is sent.")
                Reason(icon: "dot.radiowaves.left.and.right", title: "Open data",
                       text: "Positions come from volunteer ADS-B receivers worldwide.")
            }
            Spacer()
            if locationDenied {
                Text("Location is turned off for Flight View. Turn it on in Settings to see nearby aircraft.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            } else {
                Button(action: onContinue) {
                    Text("Continue").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.accent)
                .foregroundStyle(.black)
            }
        }
        .padding(24)
    }
}

private struct Reason: View {
    let icon: String
    let title: LocalizedStringKey
    let text: LocalizedStringKey
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).font(.title2).frame(width: 32).foregroundStyle(Theme.accent)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
