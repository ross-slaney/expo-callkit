import {
  AndroidManifestShape,
  CALL_EVENT_BROADCAST_ACTION,
  DEFAULT_MICROPHONE_TEXT,
  applyAndroidManifestEntries,
  applyEntitlementEntries,
  applyInfoPlistEntries,
  resolveProps,
} from "../../plugin/src/config";

describe("resolveProps", () => {
  it("applies documented defaults", () => {
    expect(resolveProps(undefined)).toEqual({
      microphonePermissionText: DEFAULT_MICROPHONE_TEXT,
      incomingCallTimeout: 45,
      answerFulfillTimeout: 30,
      outgoingCallTimeout: 60,
    });
  });

  it("keeps explicit values and rounds timeouts", () => {
    const resolved = resolveProps({
      microphonePermissionText: "Mic please",
      incomingCallTimeout: 20.6,
      answerFulfillTimeout: 10,
      outgoingCallTimeout: 90,
      ringtone: "ring.caf",
      androidCallEventReceiver: "com.example.CallEventsReceiver",
    });
    expect(resolved).toEqual({
      microphonePermissionText: "Mic please",
      incomingCallTimeout: 21,
      answerFulfillTimeout: 10,
      outgoingCallTimeout: 90,
      ringtone: "ring.caf",
      androidCallEventReceiver: "com.example.CallEventsReceiver",
    });
  });

  it("falls back on invalid timeouts", () => {
    expect(resolveProps({ incomingCallTimeout: -5 }).incomingCallTimeout).toBe(
      45,
    );
    expect(resolveProps({ outgoingCallTimeout: NaN }).outgoingCallTimeout).toBe(
      60,
    );
  });
});

describe("applyInfoPlistEntries", () => {
  it("writes background modes, mic text and timeout keys", () => {
    const result = applyInfoPlistEntries({}, resolveProps(undefined));
    expect(result).toMatchSnapshot();
  });

  it("merges UIBackgroundModes without duplicates", () => {
    const result = applyInfoPlistEntries(
      { UIBackgroundModes: ["audio", "fetch"] },
      resolveProps(undefined),
    );
    expect(result.UIBackgroundModes).toEqual(["audio", "fetch", "voip"]);
  });

  it("does not clobber an existing microphone description", () => {
    const result = applyInfoPlistEntries(
      { NSMicrophoneUsageDescription: "Existing" },
      resolveProps({ microphonePermissionText: "New" }),
    );
    expect(result.NSMicrophoneUsageDescription).toBe("Existing");
  });

  it("only writes the ringtone key when configured", () => {
    expect(
      applyInfoPlistEntries({}, resolveProps(undefined)).ExpoCallKitRingtone,
    ).toBeUndefined();
    expect(
      applyInfoPlistEntries({}, resolveProps({ ringtone: "ring.caf" }))
        .ExpoCallKitRingtone,
    ).toBe("ring.caf");
  });
});

describe("applyEntitlementEntries", () => {
  it("defaults aps-environment to development", () => {
    expect(applyEntitlementEntries({})["aps-environment"]).toBe("development");
  });

  it("respects an existing aps-environment", () => {
    expect(
      applyEntitlementEntries({ "aps-environment": "production" })[
        "aps-environment"
      ],
    ).toBe("production");
  });
});

describe("applyAndroidManifestEntries", () => {
  const makeManifest = (): AndroidManifestShape => ({
    manifest: { application: [{ $: { "android:name": ".MainApplication" } }] },
  });

  it("adds timeout meta-data entries", () => {
    const manifest = applyAndroidManifestEntries(
      makeManifest(),
      resolveProps({ incomingCallTimeout: 30 }),
    );
    expect(manifest.manifest.application?.[0]["meta-data"]).toEqual([
      {
        $: {
          "android:name": "ExpoCallKitIncomingTimeout",
          "android:value": "30",
        },
      },
      {
        $: {
          "android:name": "ExpoCallKitAnswerFulfillTimeout",
          "android:value": "30",
        },
      },
      {
        $: {
          "android:name": "ExpoCallKitOutgoingTimeout",
          "android:value": "60",
        },
      },
    ]);
  });

  it("is idempotent (upserts, no duplicates)", () => {
    const once = applyAndroidManifestEntries(
      makeManifest(),
      resolveProps(undefined),
    );
    const twice = applyAndroidManifestEntries(
      once,
      resolveProps({ incomingCallTimeout: 15 }),
    );
    const metaData = twice.manifest.application?.[0]["meta-data"];
    expect(metaData).toHaveLength(3);
    expect(
      metaData?.find(
        (m) => m.$["android:name"] === "ExpoCallKitIncomingTimeout",
      )?.$["android:value"],
    ).toBe("15");
  });

  it("registers the optional call-event receiver with the intent filter", () => {
    const manifest = applyAndroidManifestEntries(
      makeManifest(),
      resolveProps({
        androidCallEventReceiver: "com.example.CallEventsReceiver",
      }),
    );
    const receivers = manifest.manifest.application?.[0].receiver;
    expect(receivers).toHaveLength(1);
    expect(receivers?.[0]).toEqual({
      $: {
        "android:name": "com.example.CallEventsReceiver",
        "android:exported": "false",
      },
      "intent-filter": [
        { action: [{ $: { "android:name": CALL_EVENT_BROADCAST_ACTION } }] },
      ],
    });

    // Idempotent on re-run.
    const again = applyAndroidManifestEntries(
      manifest,
      resolveProps({
        androidCallEventReceiver: "com.example.CallEventsReceiver",
      }),
    );
    expect(again.manifest.application?.[0].receiver).toHaveLength(1);
  });

  it("throws when the manifest has no application element", () => {
    expect(() =>
      applyAndroidManifestEntries({ manifest: {} }, resolveProps(undefined)),
    ).toThrow(/no <application>/);
  });
});
