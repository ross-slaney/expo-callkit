import * as CallKit from "@ross-slaney/expo-callkit";
import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";

/**
 * Replace this adapter with a real ACS/WebRTC/SIP implementation. The safe
 * default rejects answers: the example never tells Telecom that silent media
 * is connected.
 */
const media = {
  async prepare(
    _serverCallId: string,
    _rawPushPayload?: Record<string, unknown>
  ): Promise<void> {},
  async join(
    _serverCallId: string,
    _rawPushPayload?: Record<string, unknown>
  ): Promise<void> {
    throw new Error("Connect a real media adapter before acknowledging calls");
  },
  startAudio(): void {},
  stopAudio(): void {},
  async hangup(_serverCallId: string): Promise<void> {},
};

const EMPTY_AUDIO_ROUTE_STATE: CallKit.AudioRouteState = {
  callId: null,
  isAudioActive: false,
  currentRoute: null,
  availableRoutes: [],
  supportsRouteSelection: false,
  supportsSpeakerOverride: false,
};

export default function App() {
  const [events, setEvents] = useState<string[]>([]);
  const [audioRoute, setAudioRoute] = useState<CallKit.AudioRouteState>(
    EMPTY_AUDIO_ROUTE_STATE
  );
  const serverCalls = useRef(new Map<string, string>());

  const log = (message: string) => {
    setEvents((current) => [message, ...current].slice(0, 20));
  };

  useEffect(() => {
    CallKit.configureAudioSession();
    CallKit.registerVoipPushes();

    void CallKit.requestPermissions()
      .then(({ notifications }) =>
        log(`Notification permission: ${notifications}`)
      )
      .catch((error) =>
        log(
          `Permission request failed: ${
            error instanceof Error ? error.message : String(error)
          }`
        )
      );

    const existingToken = CallKit.getVoipToken();
    if (existingToken) {
      log("VoIP token ready");
    }

    void CallKit.getAudioRouteState().then(setAudioRoute).catch((error) =>
      log(
        `Audio route query failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      )
    );

    const subscriptions = [
      CallKit.addCallKitListener(
        "onIncomingCall",
        ({ callId, payload, rawPushPayload }) => {
          serverCalls.current.set(callId, payload.serverCallId);
          void media
            .prepare(payload.serverCallId, rawPushPayload)
            .catch((error) =>
              log(
                `Media preparation failed: ${
                  error instanceof Error ? error.message : String(error)
                }`
              )
            );
          log(`Incoming: ${payload.caller.displayName ?? payload.caller.id}`);
        }
      ),
      CallKit.addCallKitListener(
        "onCallAnswered",
        async ({ callId, requestId, rawPushPayload }) => {
          const serverCallId = serverCalls.current.get(callId);
          try {
            if (!serverCallId) {
              throw new Error("No server call mapping was received");
            }
            await media.join(serverCallId, rawPushPayload);
            await CallKit.answerAcknowledged(requestId);
            log("Media ready; native answer acknowledged");
          } catch (error) {
            await CallKit.answerFailed(requestId);
            const message =
              error instanceof Error ? error.message : "Media join failed";
            log(`Answer rejected safely: ${message}`);
          }
        }
      ),
      CallKit.addCallKitListener("onAudioSessionActivated", () => {
        media.startAudio();
        log("Audio session activated");
      }),
      CallKit.addCallKitListener("onAudioSessionDeactivated", () => {
        media.stopAudio();
        log("Audio session deactivated");
      }),
      CallKit.addCallKitListener("onAudioRouteChanged", (state) => {
        setAudioRoute(state);
        if (state.currentRoute) {
          log(`Audio route: ${state.currentRoute.name}`);
        }
      }),
      CallKit.addCallKitListener(
        "onCallEnded",
        ({ callId, session, reason }) => {
          const serverCallId =
            session.serverCallId ?? serverCalls.current.get(callId);
          serverCalls.current.delete(callId);
          if (serverCallId) {
            void media
              .hangup(serverCallId)
              .catch((error) =>
                log(
                  `Media hangup failed: ${
                    error instanceof Error ? error.message : String(error)
                  }`
                )
              );
          }
          log(`Ended: ${reason}`);
        }
      ),
      CallKit.addCallKitListener("onVoipTokenUpdated", ({ token }) => {
        log(token ? "VoIP token ready" : "VoIP token invalidated");
      }),
    ];

    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, []);

  const simulateIncoming = async () => {
    const id = Date.now().toString(36);
    try {
      const { notifications } = await CallKit.requestPermissions();
      if (notifications !== "granted") {
        throw new Error(
          "Notification permission is required to show an incoming call"
        );
      }
      await CallKit.reportIncomingCall({
        eventId: `example-event-${id}`,
        serverCallId: `example-call-${id}`,
        caller: { id: "example-caller", displayName: "Example Caller" },
      });
    } catch (error) {
      Alert.alert(
        "Could not report call",
        error instanceof Error ? error.message : String(error)
      );
    }
  };

  const chooseAudioRoute = async (route: CallKit.AudioRoute) => {
    if (!audioRoute.callId || !audioRoute.supportsRouteSelection) {
      return;
    }
    try {
      await CallKit.selectAudioRoute(audioRoute.callId, route.id);
    } catch (error) {
      log(
        `Route change failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
      setAudioRoute(await CallKit.getAudioRouteState());
    }
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.eyebrow}>PHYSICAL DEVICE HARNESS</Text>
        <Text style={styles.title}>Expo CallKit</Text>
        <Text style={styles.body}>
          This button exercises native call UI only. Answering fails safely
          until you replace the media adapter with a real transport.
        </Text>

        <Pressable
          style={styles.button}
          onPress={() => void simulateIncoming()}
        >
          <Text style={styles.buttonText}>Simulate incoming call</Text>
        </Pressable>

        <View style={styles.logCard}>
          <Text style={styles.logTitle}>Call audio</Text>
          <Text style={styles.logLine}>
            {audioRoute.isAudioActive
              ? `Current: ${audioRoute.currentRoute?.name ?? "Unknown"}`
              : "Waiting for the OS to activate call audio."}
          </Text>
          <View style={styles.routeRow}>
            {audioRoute.availableRoutes.map((route) => {
              const selected = route.id === audioRoute.currentRoute?.id;
              return (
                <Pressable
                  key={route.id}
                  disabled={!audioRoute.supportsRouteSelection}
                  onPress={() => void chooseAudioRoute(route)}
                  style={[
                    styles.routeButton,
                    selected && styles.routeButtonSelected,
                  ]}
                >
                  <Text
                    style={[
                      styles.routeButtonText,
                      selected && styles.routeButtonTextSelected,
                    ]}
                  >
                    {route.name}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>

        <View style={styles.logCard}>
          <Text style={styles.logTitle}>Lifecycle events</Text>
          {events.length === 0 ? (
            <Text style={styles.logLine}>No events yet.</Text>
          ) : (
            events.map((event, index) => (
              <Text key={`${index}-${event}`} style={styles.logLine}>
                {event}
              </Text>
            ))
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: "#08111f" },
  content: { flexGrow: 1, padding: 24, justifyContent: "center", gap: 18 },
  eyebrow: {
    color: "#60a5fa",
    fontSize: 12,
    fontWeight: "700",
    letterSpacing: 1.5,
  },
  title: { color: "#f8fafc", fontSize: 38, fontWeight: "800" },
  body: { color: "#cbd5e1", fontSize: 17, lineHeight: 25 },
  button: { backgroundColor: "#2563eb", borderRadius: 14, padding: 16 },
  buttonText: {
    color: "white",
    textAlign: "center",
    fontSize: 16,
    fontWeight: "700",
  },
  logCard: {
    backgroundColor: "#111c2e",
    borderRadius: 14,
    padding: 16,
    gap: 8,
  },
  logTitle: { color: "#f8fafc", fontSize: 16, fontWeight: "700" },
  logLine: { color: "#94a3b8", fontSize: 13, lineHeight: 18 },
  routeRow: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  routeButton: {
    borderColor: "#334155",
    borderRadius: 999,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  routeButtonSelected: { backgroundColor: "#dbeafe", borderColor: "#60a5fa" },
  routeButtonText: { color: "#cbd5e1", fontSize: 13, fontWeight: "600" },
  routeButtonTextSelected: { color: "#1d4ed8" },
});
