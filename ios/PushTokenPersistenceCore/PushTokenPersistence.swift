import Foundation

/// APNs environments produce different device tokens for the same app and
/// device. A token restored under the other environment is never usable.
enum APNSEnvironment: String, Codable, Equatable {
  case development
  case production

  /// Extracts the signed `aps-environment` entitlement from the embedded
  /// provisioning profile. The profile is a CMS envelope containing a plist.
  static func fromProvisioningProfile(_ data: Data) -> APNSEnvironment? {
    let opening = Data("<plist".utf8)
    let closing = Data("</plist>".utf8)
    guard
      let openingRange = data.range(of: opening),
      let closingRange = data.range(of: closing, in: openingRange.lowerBound..<data.endIndex)
    else {
      return nil
    }

    let plistData = data[openingRange.lowerBound..<closingRange.upperBound]
    guard
      let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil),
      let root = plist as? [String: Any],
      let entitlements = root["Entitlements"] as? [String: Any],
      let rawEnvironment = entitlements["aps-environment"] as? String
    else {
      return nil
    }

    return APNSEnvironment(
      rawValue: rawEnvironment.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    )
  }
}

struct PushTokenRestoration: Equatable {
  let token: String?
  let shouldRemovePersistedValue: Bool
}

/// Versioned codec for a PushKit token and the APNs environment that issued it.
/// UserDefaults stores the encoded Data; malformed or cross-environment state
/// fails closed and is deleted by the coordinator.
enum PushTokenPersistence {
  private static let schemaVersion = 1

  private struct Record: Codable {
    let schemaVersion: Int
    let token: String
    let environment: APNSEnvironment
  }

  static func encode(token: String, environment: APNSEnvironment) -> Data? {
    guard let normalizedToken = normalize(token) else {
      return nil
    }
    return try? JSONEncoder().encode(Record(
      schemaVersion: schemaVersion,
      token: normalizedToken,
      environment: environment
    ))
  }

  static func restore(
    data: Data?,
    currentEnvironment: APNSEnvironment?
  ) -> PushTokenRestoration {
    guard let data else {
      return PushTokenRestoration(token: nil, shouldRemovePersistedValue: false)
    }
    guard
      let currentEnvironment,
      let record = try? JSONDecoder().decode(Record.self, from: data),
      record.schemaVersion == schemaVersion,
      record.environment == currentEnvironment,
      let token = normalize(record.token)
    else {
      return PushTokenRestoration(token: nil, shouldRemovePersistedValue: true)
    }
    return PushTokenRestoration(token: token, shouldRemovePersistedValue: false)
  }

  private static func normalize(_ token: String) -> String? {
    let normalized = token.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard
      !normalized.isEmpty,
      normalized.unicodeScalars.allSatisfy({ scalar in
        (48...57).contains(scalar.value) || (97...102).contains(scalar.value)
      })
    else {
      return nil
    }
    return normalized
  }
}
