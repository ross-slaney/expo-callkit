import XCTest

@testable import ExpoCallKitPushTokenPersistence

final class PushTokenPersistenceTests: XCTestCase {
  func testRestoresTokenOnlyIntoTheEnvironmentThatIssuedIt() throws {
    let data = try XCTUnwrap(PushTokenPersistence.encode(
      token: "A1B2C3D4",
      environment: .development
    ))

    XCTAssertEqual(
      PushTokenPersistence.restore(data: data, currentEnvironment: .development),
      PushTokenRestoration(token: "a1b2c3d4", shouldRemovePersistedValue: false)
    )
  }

  func testRejectsAndRemovesCrossEnvironmentToken() throws {
    let data = try XCTUnwrap(PushTokenPersistence.encode(
      token: "a1b2c3d4",
      environment: .development
    ))

    XCTAssertEqual(
      PushTokenPersistence.restore(data: data, currentEnvironment: .production),
      PushTokenRestoration(token: nil, shouldRemovePersistedValue: true)
    )
  }

  func testRejectsPersistedTokenWhenRuntimeEnvironmentIsUnknown() throws {
    let data = try XCTUnwrap(PushTokenPersistence.encode(
      token: "a1b2c3d4",
      environment: .production
    ))

    XCTAssertEqual(
      PushTokenPersistence.restore(data: data, currentEnvironment: nil),
      PushTokenRestoration(token: nil, shouldRemovePersistedValue: true)
    )
  }

  func testRejectsMalformedPersistedValue() {
    XCTAssertEqual(
      PushTokenPersistence.restore(
        data: Data("not-json".utf8),
        currentEnvironment: .production
      ),
      PushTokenRestoration(token: nil, shouldRemovePersistedValue: true)
    )
  }

  func testNoStoredValueNeedsNoCleanup() {
    XCTAssertEqual(
      PushTokenPersistence.restore(data: nil, currentEnvironment: .production),
      PushTokenRestoration(token: nil, shouldRemovePersistedValue: false)
    )
  }

  func testReadsEnvironmentFromProvisioningProfileEnvelope() {
    let profile = Data("""
      binary-prefix
      <plist version="1.0">
      <dict>
        <key>Entitlements</key>
        <dict><key>aps-environment</key><string>production</string></dict>
      </dict>
      </plist>
      binary-suffix
      """.utf8)

    XCTAssertEqual(APNSEnvironment.fromProvisioningProfile(profile), .production)
    XCTAssertNil(APNSEnvironment.fromProvisioningProfile(Data("invalid".utf8)))
  }
}
