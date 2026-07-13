import type {
  CallAnsweredEvent,
  CallEndedEvent,
  IncomingCallEvent,
} from "../ExpoCallKit.types";
import {
  CallProviderRouter,
  CallProviderSelectionError,
  type CallProviderAdapter,
} from "../provider-router";

const meta = { flushed: false, timestamp: "2026-07-12T00:00:00.000Z" };

function incoming(provider = "telnyx"): IncomingCallEvent {
  return {
    callId: "call-1",
    payload: {
      eventId: "event-1",
      serverCallId: "server-1",
      provider,
      caller: { id: "caller-1", displayName: "Caller" },
    },
    rawPushPayload: { provider, opaque: "sensitive" },
    meta,
  };
}

function answered(provider = "telnyx"): CallAnsweredEvent {
  return {
    ...incoming(provider),
    requestId: "request-1",
  };
}

function ended(provider = "telnyx"): CallEndedEvent {
  return {
    callId: "call-1",
    reason: "local",
    rawPushPayload: { provider, opaque: "sensitive" },
    session: {
      id: "call-1",
      origin: "incoming",
      status: "ended",
      provider,
      serverCallId: "server-1",
      caller: { id: "caller-1" },
      isMuted: false,
      isOnHold: false,
    },
    meta,
  };
}

type MockAdapter = CallProviderAdapter & {
  matches: jest.Mock;
  prepareIncoming: jest.Mock;
  answerIncoming: jest.Mock;
  endCall: jest.Mock;
  setMuted: jest.Mock;
  setOnHold: jest.Mock;
  sendDtmf: jest.Mock;
  activateAudio: jest.Mock;
  deactivateAudio: jest.Mock;
};

function adapter(id: string): MockAdapter {
  return {
    id,
    matches: jest.fn((context) => context.provider === id),
    prepareIncoming: jest.fn(async () => {}),
    answerIncoming: jest.fn(async () => {}),
    endCall: jest.fn(async () => {}),
    setMuted: jest.fn(async () => {}),
    setOnHold: jest.fn(async () => {}),
    sendDtmf: jest.fn(async () => {}),
    activateAudio: jest.fn(async () => {}),
    deactivateAudio: jest.fn(async () => {}),
  };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe("CallProviderRouter", () => {
  it("routes a complete lifecycle to exactly one of two providers", async () => {
    const acs = adapter("acs");
    const telnyx = adapter("telnyx");
    const acknowledge = jest.fn(async () => {});
    const fail = jest.fn(async () => {});
    const router = new CallProviderRouter([acs, telnyx]);

    router.onIncoming(incoming());
    await router.onAnswered(answered(), { acknowledge, fail });
    router.onMuteChanged({ callId: "call-1", isMuted: true, meta });
    router.onHoldChanged({ callId: "call-1", isOnHold: true, meta });
    router.onDtmf({ callId: "call-1", digits: "12#", meta });
    router.onAudioActivated();
    await flush();
    router.onAudioDeactivated();
    router.onEnded(ended());
    await flush();

    expect(acs.answerIncoming).not.toHaveBeenCalled();
    expect(telnyx.prepareIncoming).toHaveBeenCalledWith(
      expect.objectContaining({
        provider: "telnyx",
        serverCallId: "server-1",
        rawPushPayload: { provider: "telnyx", opaque: "sensitive" },
      }),
    );
    expect(telnyx.answerIncoming).toHaveBeenCalledTimes(1);
    expect(acknowledge).toHaveBeenCalledWith("request-1");
    expect(fail).not.toHaveBeenCalled();
    expect(telnyx.setMuted).toHaveBeenCalledWith(expect.any(Object), true);
    expect(telnyx.setOnHold).toHaveBeenCalledWith(expect.any(Object), true);
    expect(telnyx.sendDtmf).toHaveBeenCalledWith(expect.any(Object), "12#");
    expect(telnyx.activateAudio).toHaveBeenCalledTimes(1);
    expect(telnyx.deactivateAudio).toHaveBeenCalledTimes(1);
    expect(telnyx.endCall).toHaveBeenCalledWith(expect.any(Object), "local");
  });

  it("supports direct carrier pushes through an adapter raw-shape matcher", async () => {
    const telnyx = adapter("telnyx");
    telnyx.matches.mockImplementation(
      (context) => context.rawPushPayload?.voice_sdk_id === "voice-1",
    );
    const router = new CallProviderRouter([telnyx]);
    const event = incoming("");
    delete event.payload.provider;
    event.rawPushPayload = { voice_sdk_id: "voice-1" };

    router.onIncoming(event);
    await router.onAnswered(
      { ...event, requestId: "request-1" },
      { acknowledge: jest.fn(async () => {}), fail: jest.fn(async () => {}) },
    );

    expect(telnyx.answerIncoming).toHaveBeenCalledWith(
      expect.objectContaining({ rawPushPayload: { voice_sdk_id: "voice-1" } }),
    );
  });

  it("selects canonical provider ids without requiring a shape matcher", async () => {
    const acs: CallProviderAdapter = {
      id: "acs",
      answerIncoming: jest.fn(async () => {}),
    };
    const acknowledge = jest.fn(async () => {});
    const router = new CallProviderRouter([acs]);

    await router.onAnswered(answered("acs"), {
      acknowledge,
      fail: jest.fn(async () => {}),
    });

    expect(acs.answerIncoming).toHaveBeenCalledTimes(1);
    expect(acknowledge).toHaveBeenCalledTimes(1);
  });

  it("treats an explicit provider as authoritative over raw-shape matchers", async () => {
    const acs = adapter("acs");
    const telnyx = adapter("telnyx");
    telnyx.matches.mockReturnValue(true);
    const router = new CallProviderRouter([acs, telnyx]);

    await router.onAnswered(answered("acs"), {
      acknowledge: jest.fn(async () => {}),
      fail: jest.fn(async () => {}),
    });

    expect(acs.answerIncoming).toHaveBeenCalledTimes(1);
    expect(telnyx.answerIncoming).not.toHaveBeenCalled();
  });

  it("fails the native answer when no provider matches", async () => {
    const acs = adapter("acs");
    const fail = jest.fn(async () => {});
    const errors: unknown[] = [];
    const router = new CallProviderRouter([acs], {
      onError: ({ error }) => errors.push(error),
    });

    await router.onAnswered(answered("telnyx"), {
      acknowledge: jest.fn(async () => {}),
      fail,
    });

    expect(fail).toHaveBeenCalledWith("request-1");
    expect(errors[0]).toBeInstanceOf(CallProviderSelectionError);
    expect((errors[0] as CallProviderSelectionError).code).toBe(
      "ERR_NO_CALL_PROVIDER",
    );
  });

  it("still fails the native answer when an observability callback throws", async () => {
    const fail = jest.fn(async () => {});
    const router = new CallProviderRouter([adapter("acs")], {
      onError: () => {
        throw new Error("telemetry unavailable");
      },
    });

    await router.onAnswered(answered("telnyx"), {
      acknowledge: jest.fn(async () => {}),
      fail,
    });

    expect(fail).toHaveBeenCalledWith("request-1");
  });

  it("fails closed when two adapters claim the same call", async () => {
    const first = adapter("first");
    const second = adapter("second");
    first.matches.mockReturnValue(true);
    second.matches.mockReturnValue(true);
    const fail = jest.fn(async () => {});
    const router = new CallProviderRouter([first, second]);
    const event = answered();
    if (event.payload) delete event.payload.provider;

    await router.onAnswered(event, {
      acknowledge: jest.fn(async () => {}),
      fail,
    });

    expect(fail).toHaveBeenCalledTimes(1);
    expect(first.answerIncoming).not.toHaveBeenCalled();
    expect(second.answerIncoming).not.toHaveBeenCalled();
  });

  it("does not acknowledge when provider preparation failed", async () => {
    const telnyx = adapter("telnyx");
    telnyx.prepareIncoming.mockRejectedValue(new Error("offline"));
    const acknowledge = jest.fn(async () => {});
    const fail = jest.fn(async () => {});
    const router = new CallProviderRouter([telnyx]);

    router.onIncoming(incoming());
    await router.onAnswered(answered(), { acknowledge, fail });

    expect(telnyx.answerIncoming).not.toHaveBeenCalled();
    expect(acknowledge).not.toHaveBeenCalled();
    expect(fail).toHaveBeenCalledTimes(1);
  });

  it("can select and answer from a cold-start answer event alone", async () => {
    const acs = adapter("acs");
    const router = new CallProviderRouter([acs]);
    const acknowledge = jest.fn(async () => {});

    await router.onAnswered(answered("acs"), {
      acknowledge,
      fail: jest.fn(async () => {}),
    });

    expect(acs.prepareIncoming).not.toHaveBeenCalled();
    expect(acs.answerIncoming).toHaveBeenCalledTimes(1);
    expect(acknowledge).toHaveBeenCalledTimes(1);
  });

  it("can route a killed-state decline from the ended session alone", async () => {
    const acs = adapter("acs");
    const router = new CallProviderRouter([acs]);

    router.onEnded(ended("acs"));
    await flush();

    expect(acs.endCall).toHaveBeenCalledWith(
      expect.objectContaining({ provider: "acs", serverCallId: "server-1" }),
      "local",
    );
  });

  it("rejects an empty or ambiguous adapter registry", () => {
    expect(() => new CallProviderRouter([])).toThrow(
      CallProviderSelectionError,
    );
    expect(
      () => new CallProviderRouter([adapter("same"), adapter("same")]),
    ).toThrow(CallProviderSelectionError);
  });
});
