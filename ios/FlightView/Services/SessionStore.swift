import Foundation
import Supabase

/// Owns the Supabase client. Every install signs in anonymously on first use; the SDK
/// persists the session in the Keychain and refreshes tokens automatically.
@MainActor
final class SessionStore {
    let client: SupabaseClient

    init(config: AppConfig) {
        client = SupabaseClient(supabaseURL: config.supabaseURL, supabaseKey: config.supabaseAnonKey)
    }

    func accessToken() async throws -> String {
        if let session = try? await client.auth.session {
            return session.accessToken
        }
        return try await client.auth.signInAnonymously().accessToken
    }
}
