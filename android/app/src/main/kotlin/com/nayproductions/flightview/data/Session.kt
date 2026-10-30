package com.nayproductions.flightview.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supabase client. Every install signs in anonymously on first use; the SDK persists
 * and refreshes the session.
 */
class Session(url: String, anonKey: String) {
    val client = createSupabaseClient(supabaseUrl = url, supabaseKey = anonKey) {
        install(Auth)
        install(Postgrest)
    }

    private val signInLock = Mutex()

    suspend fun accessToken(): String = signInLock.withLock {
        client.auth.awaitInitialization()
        client.auth.currentAccessTokenOrNull() ?: run {
            client.auth.signInAnonymously()
            requireNotNull(client.auth.currentAccessTokenOrNull()) { "Anonymous sign-in returned no session" }
        }
    }
}
