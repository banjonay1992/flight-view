package com.nayproductions.flightview

import android.app.Application
import com.nayproductions.flightview.data.AircraftApi
import com.nayproductions.flightview.data.Logbook
import com.nayproductions.flightview.data.Session
import com.nayproductions.flightview.sensors.LocationSource
import com.nayproductions.flightview.sensors.OrientationSensor

class FlightViewApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    init {
        check(BuildConfig.API_BASE_URL.isNotBlank() && BuildConfig.SUPABASE_URL.isNotBlank()) {
            "Missing endpoints: copy android/secrets.example.properties to android/secrets.properties"
        }
    }

    val session = Session(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    val api = AircraftApi(BuildConfig.API_BASE_URL, session)
    val logbook = Logbook(session)
    val location = LocationSource(app)
    val orientation = OrientationSensor(app)
}
