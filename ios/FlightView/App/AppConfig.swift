import Foundation

/// Build-time configuration, injected from Config/*.xcconfig through Info.plist.
/// Nothing secret lives here: the Supabase anon key is public by design and protected by RLS.
struct AppConfig {
    let apiBaseURL: URL
    let supabaseURL: URL
    let supabaseAnonKey: String

    static func fromBundle(_ bundle: Bundle = .main) -> AppConfig {
        func value(_ key: String) -> String {
            guard let v = bundle.object(forInfoDictionaryKey: key) as? String, !v.isEmpty, !v.hasPrefix("$(") else {
                fatalError("Missing \(key). Copy Config/Secrets.example.xcconfig to Config/Secrets.xcconfig and fill it in.")
            }
            return v
        }
        guard let api = URL(string: value("FVApiBaseURL")), let supa = URL(string: value("FVSupabaseURL")) else {
            fatalError("FVApiBaseURL and FVSupabaseURL must be valid URLs")
        }
        return AppConfig(apiBaseURL: api, supabaseURL: supa, supabaseAnonKey: value("FVSupabaseAnonKey"))
    }
}
