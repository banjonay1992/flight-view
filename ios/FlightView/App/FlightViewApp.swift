import SwiftUI

@main
@MainActor
struct FlightViewApp: App {
    @State private var environment = AppEnvironment.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(environment)
                .preferredColorScheme(.dark)
        }
    }
}

/// Dependency container. Built once; views read it from the environment.
@Observable
@MainActor
final class AppEnvironment {
    let config: AppConfig
    let session: SessionStore
    let api: AircraftAPI
    let location: LocationService
    let motion: MotionService
    let camera: CameraService
    let logbook: LogbookService

    init(config: AppConfig, session: SessionStore, api: AircraftAPI, location: LocationService,
         motion: MotionService, camera: CameraService, logbook: LogbookService) {
        self.config = config
        self.session = session
        self.api = api
        self.location = location
        self.motion = motion
        self.camera = camera
        self.logbook = logbook
    }

    static func live() -> AppEnvironment {
        let config = AppConfig.fromBundle()
        let session = SessionStore(config: config)
        return AppEnvironment(
            config: config,
            session: session,
            api: AircraftAPI(baseURL: config.apiBaseURL, session: session),
            location: LocationService(),
            motion: MotionService(),
            camera: CameraService(),
            logbook: LogbookService(session: session)
        )
    }
}
