import Foundation
import SwiftUI

enum Theme {
    static let accent = Color(red: 1.0, green: 0.72, blue: 0.18)
}

/// Aviation convention: altitude in feet, speed in knots. Distance follows the user's locale.
enum Fmt {
    static func altitude(_ metres: Double) -> String {
        let ft = Measurement(value: metres, unit: UnitLength.meters).converted(to: .feet)
        return ft.formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                         numberFormatStyle: .number.precision(.significantDigits(3))))
    }

    static func distance(_ metres: Double) -> String {
        Measurement(value: metres, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .road,
                                    numberFormatStyle: .number.precision(.fractionLength(0...1))))
    }

    static func speed(_ knots: Double) -> String {
        Measurement(value: knots, unit: UnitSpeed.knots)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                    numberFormatStyle: .number.precision(.fractionLength(0))))
    }

    static func compass(_ deg: Double) -> String {
        let names = [
            String(localized: "N"), String(localized: "NE"), String(localized: "E"), String(localized: "SE"),
            String(localized: "S"), String(localized: "SW"), String(localized: "W"), String(localized: "NW"),
        ]
        let d = (deg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return "\(names[Int((d / 45).rounded()) % 8]) (\(Int(d.rounded()))°)"
    }

    /// Relative bearing as a clock position: 12 is straight ahead.
    static func clock(relativeDeg: Double) -> String {
        let d = (relativeDeg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        var hour = Int((d / 30).rounded()) % 12
        if hour == 0 { hour = 12 }
        return String(localized: "\(hour) o'clock")
    }
}
