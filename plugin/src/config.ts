/**
 * Pure config transforms — no expo/config-plugins imports so they are unit
 * testable in isolation. `index.ts` wires them into the mod pipeline.
 */

export type ExpoCallKitPluginProps = {
  /** NSMicrophoneUsageDescription text. */
  microphonePermissionText?: string;
  /** Seconds an incoming call rings before auto-ending as unanswered (default 45). */
  incomingCallTimeout?: number;
  /** Seconds the module waits for answerAcknowledged before failing the answer (default 30). */
  answerFulfillTimeout?: number;
  /** Seconds an outgoing call may stay unconnected before auto-ending (default 60). */
  outgoingCallTimeout?: number;
  /**
   * Ringtone: iOS bundle sound filename / Android raw resource name.
   * Omit for the system default.
   */
  ringtone?: string;
  /**
   * Fully-qualified class name of an Android BroadcastReceiver in your app
   * that should receive dev.rossslaney.expocallkit.CALL_EVENT broadcasts
   * (terminal call events fired while no JS listener is alive).
   */
  androidCallEventReceiver?: string;
};

export type ResolvedPluginProps = {
  microphonePermissionText: string;
  incomingCallTimeout: number;
  answerFulfillTimeout: number;
  outgoingCallTimeout: number;
  ringtone?: string;
  androidCallEventReceiver?: string;
};

export const DEFAULT_MICROPHONE_TEXT =
  "Allow $(PRODUCT_NAME) to access the microphone during calls";

export const CALL_EVENT_BROADCAST_ACTION =
  "dev.rossslaney.expocallkit.CALL_EVENT";

const IOS_BACKGROUND_MODES = ["voip", "audio"];

const TIMEOUT_KEYS = {
  incoming: "ExpoCallKitIncomingTimeout",
  answerFulfill: "ExpoCallKitAnswerFulfillTimeout",
  outgoing: "ExpoCallKitOutgoingTimeout",
} as const;

const RINGTONE_KEY = "ExpoCallKitRingtone";

function positiveInteger(value: number | undefined, fallback: number): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    return fallback;
  }
  return Math.round(value);
}

export function resolveProps(
  props?: ExpoCallKitPluginProps,
): ResolvedPluginProps {
  const resolved: ResolvedPluginProps = {
    microphonePermissionText:
      props?.microphonePermissionText?.trim() || DEFAULT_MICROPHONE_TEXT,
    incomingCallTimeout: positiveInteger(props?.incomingCallTimeout, 45),
    answerFulfillTimeout: positiveInteger(props?.answerFulfillTimeout, 30),
    outgoingCallTimeout: positiveInteger(props?.outgoingCallTimeout, 60),
  };
  if (props?.ringtone?.trim()) {
    resolved.ringtone = props.ringtone.trim();
  }
  if (props?.androidCallEventReceiver?.trim()) {
    resolved.androidCallEventReceiver = props.androidCallEventReceiver.trim();
  }
  return resolved;
}

/** Adds background modes, mic usage text and ExpoCallKit* keys to Info.plist. */
export function applyInfoPlistEntries(
  plist: Record<string, unknown>,
  props: ResolvedPluginProps,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...plist };

  const existingModes = Array.isArray(next.UIBackgroundModes)
    ? (next.UIBackgroundModes as unknown[])
    : [];
  next.UIBackgroundModes = [
    ...existingModes,
    ...IOS_BACKGROUND_MODES.filter((mode) => !existingModes.includes(mode)),
  ];

  if (!next.NSMicrophoneUsageDescription) {
    next.NSMicrophoneUsageDescription = props.microphonePermissionText;
  }

  next[TIMEOUT_KEYS.incoming] = props.incomingCallTimeout;
  next[TIMEOUT_KEYS.answerFulfill] = props.answerFulfillTimeout;
  next[TIMEOUT_KEYS.outgoing] = props.outgoingCallTimeout;
  if (props.ringtone) {
    next[RINGTONE_KEY] = props.ringtone;
  }

  return next;
}

/**
 * Ensures aps-environment exists so PushKit registration works in dev
 * builds. Release builds get the real value from the provisioning profile.
 */
export function applyEntitlementEntries(
  entitlements: Record<string, unknown>,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...entitlements };
  if (!next["aps-environment"]) {
    next["aps-environment"] = "development";
  }
  return next;
}

// Minimal structural types for the parsed AndroidManifest.xml shape used by
// expo/config-plugins (xml2js output).
type ManifestAttrs = Record<string, string>;
type ManifestMetaData = { $: ManifestAttrs };
type ManifestReceiver = {
  $: ManifestAttrs;
  "intent-filter"?: { action: { $: ManifestAttrs }[] }[];
};
type ManifestApplication = {
  $?: ManifestAttrs;
  "meta-data"?: ManifestMetaData[];
  receiver?: ManifestReceiver[];
  [key: string]: unknown;
};
export type AndroidManifestShape = {
  manifest: {
    application?: ManifestApplication[];
    [key: string]: unknown;
  };
};

function upsertMetaData(app: ManifestApplication, name: string, value: string) {
  const items = (app["meta-data"] = app["meta-data"] ?? []);
  const existing = items.find((item) => item.$["android:name"] === name);
  if (existing) {
    existing.$["android:value"] = value;
  } else {
    items.push({ $: { "android:name": name, "android:value": value } });
  }
}

/** Adds timeout/ringtone meta-data and the optional call-event receiver. */
export function applyAndroidManifestEntries(
  manifest: AndroidManifestShape,
  props: ResolvedPluginProps,
): AndroidManifestShape {
  const app = manifest.manifest.application?.[0];
  if (!app) {
    throw new Error(
      "expo-callkit: AndroidManifest.xml has no <application> element",
    );
  }

  upsertMetaData(app, TIMEOUT_KEYS.incoming, String(props.incomingCallTimeout));
  upsertMetaData(
    app,
    TIMEOUT_KEYS.answerFulfill,
    String(props.answerFulfillTimeout),
  );
  upsertMetaData(app, TIMEOUT_KEYS.outgoing, String(props.outgoingCallTimeout));
  if (props.ringtone) {
    upsertMetaData(app, RINGTONE_KEY, props.ringtone);
  }

  if (props.androidCallEventReceiver) {
    const receivers = (app.receiver = app.receiver ?? []);
    const already = receivers.some(
      (receiver) =>
        receiver.$["android:name"] === props.androidCallEventReceiver,
    );
    if (!already) {
      receivers.push({
        $: {
          "android:name": props.androidCallEventReceiver,
          "android:exported": "false",
        },
        "intent-filter": [
          {
            action: [{ $: { "android:name": CALL_EVENT_BROADCAST_ACTION } }],
          },
        ],
      });
    }
  }

  return manifest;
}
