import type {
  CallAnsweredEvent,
  CallEndedEvent,
  CallEndReason,
  CallSession,
  DtmfEvent,
  EventMeta,
  HoldChangedEvent,
  IncomingCallEvent,
  IncomingCallPayload,
  MuteChangedEvent,
} from "./ExpoCallKit.types";

/** Provider-neutral context passed to a consuming app's media adapter. */
export type CallProviderContext = {
  callId: string;
  provider?: string;
  serverCallId?: string;
  payload?: IncomingCallPayload;
  session?: CallSession;
  rawPushPayload?: Record<string, unknown>;
  /** Native event timing, including the original timestamp after cold-start replay. */
  eventMeta?: EventMeta;
};

/**
 * Media/signaling boundary for one carrier or calling service.
 *
 * The adapter lives in the consuming app. This package deliberately never
 * imports ACS, Telnyx, LiveKit, SIP, or WebRTC implementations.
 */
export type CallProviderAdapter = {
  /** Stable key used in canonical incoming payloads, such as `acs`. */
  id: string;
  /**
   * Optional fallback for direct carrier pushes without `context.provider`.
   * Return true only when this adapter recognizes the opaque raw shape.
   */
  matches?(context: CallProviderContext): boolean;
  /** Optional signaling prefetch while the system UI is still ringing. */
  prepareIncoming?(context: CallProviderContext): void | Promise<void>;
  /** Join real signaling/media. Resolve only when an answer may be acknowledged. */
  answerIncoming(context: CallProviderContext): Promise<void>;
  /** End or reject the provider call, including killed-state decline replay. */
  endCall?(
    context: CallProviderContext,
    reason: CallEndReason,
  ): void | Promise<void>;
  setMuted?(context: CallProviderContext, muted: boolean): void | Promise<void>;
  setOnHold?(
    context: CallProviderContext,
    onHold: boolean,
  ): void | Promise<void>;
  sendDtmf?(context: CallProviderContext, digits: string): void | Promise<void>;
  /** Start audio I/O only after CallKit/Telecom activates its audio session. */
  activateAudio?(context: CallProviderContext): void | Promise<void>;
  deactivateAudio?(context: CallProviderContext): void | Promise<void>;
};

export type CallProviderPhase =
  | "select"
  | "prepare"
  | "answer"
  | "answerAcknowledgement"
  | "end"
  | "mute"
  | "hold"
  | "dtmf"
  | "activateAudio"
  | "deactivateAudio";

export type CallProviderError = {
  error: unknown;
  phase: CallProviderPhase;
  callId?: string;
  adapterId?: string;
};

export type CallProviderRouterOptions = {
  /** Called instead of logging; raw push payloads and tokens are never logged. */
  onError?(event: CallProviderError): void;
};

type AnswerBridge = {
  acknowledge(requestId: string): Promise<void>;
  fail(requestId: string): Promise<void>;
};

export class CallProviderSelectionError extends Error {
  readonly code: "ERR_NO_CALL_PROVIDER" | "ERR_AMBIGUOUS_CALL_PROVIDER";

  constructor(
    code: "ERR_NO_CALL_PROVIDER" | "ERR_AMBIGUOUS_CALL_PROVIDER",
    message: string,
  ) {
    super(message);
    this.name = "CallProviderSelectionError";
    this.code = code;
  }
}

/**
 * Routes one native CallKit/Telecom lifecycle across multiple app-owned media
 * providers. Selection is fail-closed: zero or multiple matches reject answer.
 */
export class CallProviderRouter {
  private readonly adapters: readonly CallProviderAdapter[];
  private readonly options: CallProviderRouterOptions;
  private readonly adapterByCall = new Map<string, CallProviderAdapter>();
  private readonly contextByCall = new Map<string, CallProviderContext>();
  private readonly preparationByCall = new Map<string, Promise<void>>();
  private readonly answerByCall = new Map<string, Promise<void>>();
  private readonly lifecycleTokenByCall = new Map<string, object>();
  private activeCallId: string | null = null;
  private audioActive = false;

  constructor(
    adapters: readonly CallProviderAdapter[],
    options: CallProviderRouterOptions = {},
  ) {
    if (adapters.length === 0) {
      throw new CallProviderSelectionError(
        "ERR_NO_CALL_PROVIDER",
        "At least one call provider adapter is required.",
      );
    }
    const ids = adapters.map((adapter) => adapter.id.trim());
    if (
      ids.some((id, index) => id.length === 0 || id !== adapters[index].id) ||
      new Set(ids).size !== ids.length
    ) {
      throw new CallProviderSelectionError(
        "ERR_AMBIGUOUS_CALL_PROVIDER",
        "Call provider adapter ids must be non-empty and unique.",
      );
    }
    this.adapters = adapters;
    this.options = options;
  }

  onIncoming = (event: IncomingCallEvent): void => {
    const lifecycleToken = this.lifecycleToken(event.callId);
    const context = this.mergeContext(event.callId, {
      callId: event.callId,
      provider: event.payload.provider,
      serverCallId: event.payload.serverCallId,
      payload: event.payload,
      rawPushPayload: event.rawPushPayload,
      eventMeta: event.meta,
    });
    try {
      const adapter = this.resolve(context);
      if (!adapter.prepareIncoming || this.preparationByCall.has(event.callId))
        return;
      const preparation = Promise.resolve()
        .then(() => {
          if (!this.ownsLifecycle(event.callId, lifecycleToken, adapter))
            return;
          return adapter.prepareIncoming!(context);
        })
        .catch((error) => {
          this.notify(error, "prepare", context, adapter);
          throw error;
        });
      this.preparationByCall.set(event.callId, preparation);
      preparation.catch(() => {});
    } catch (error) {
      this.notify(error, "select", context);
    }
  };

  async onAnswered(
    event: CallAnsweredEvent,
    bridge: AnswerBridge,
  ): Promise<void> {
    const context = this.mergeContext(event.callId, {
      callId: event.callId,
      provider: event.payload?.provider,
      serverCallId: event.payload?.serverCallId,
      payload: event.payload,
      rawPushPayload: event.rawPushPayload,
      eventMeta: event.meta,
    });
    let adapter: CallProviderAdapter | undefined;
    const lifecycleToken = this.lifecycleToken(event.callId);
    try {
      adapter = this.resolve(context);
      let answer = this.answerByCall.get(event.callId);
      if (!answer) {
        answer = (async () => {
          const preparation = this.preparationByCall.get(event.callId);
          if (preparation) await preparation;
          if (!this.ownsLifecycle(event.callId, lifecycleToken, adapter!))
            return;
          await adapter!.answerIncoming(context);
        })();
        this.answerByCall.set(event.callId, answer);
      }
      await answer;

      // A terminal event or unbind can win while signaling/media connects.
      // Never acknowledge or resurrect a call after its native lifecycle has
      // already ended. Keeping the shared answer promise also prevents a
      // replayed answer event from joining provider media twice.
      if (!this.ownsLifecycle(event.callId, lifecycleToken, adapter)) {
        return;
      }
      this.activeCallId = event.callId;
      await bridge.acknowledge(event.requestId);
    } catch (error) {
      this.notify(error, adapter ? "answer" : "select", context, adapter);
      if (adapter && !this.ownsLifecycle(event.callId, lifecycleToken, adapter))
        return;
      try {
        await bridge.fail(event.requestId);
      } catch (failure) {
        this.notify(failure, "answerAcknowledgement", context, adapter);
      }
    }
  }

  onEnded = (event: CallEndedEvent): void => {
    this.lifecycleTokenByCall.delete(event.callId);
    const context = this.mergeContext(event.callId, {
      callId: event.callId,
      provider: event.session.provider,
      serverCallId: event.session.serverCallId,
      session: event.session,
      rawPushPayload: event.rawPushPayload ?? event.session.rawPushPayload,
      eventMeta: event.meta,
    });
    let adapter: CallProviderAdapter | undefined;
    try {
      adapter = this.resolve(context);
    } catch (error) {
      this.notify(error, "select", context);
    }

    if (this.activeCallId === event.callId && this.audioActive) {
      if (adapter?.deactivateAudio) {
        this.invoke(
          () => adapter!.deactivateAudio!(context),
          "deactivateAudio",
          context,
          adapter,
        );
      }
      this.audioActive = false;
    }
    if (adapter?.endCall) {
      this.invoke(
        () => adapter!.endCall!(context, event.reason),
        "end",
        context,
        adapter,
      );
    }
    if (this.activeCallId === event.callId) this.activeCallId = null;
    this.adapterByCall.delete(event.callId);
    this.contextByCall.delete(event.callId);
    this.preparationByCall.delete(event.callId);
    this.answerByCall.delete(event.callId);
  };

  onMuteChanged = (event: MuteChangedEvent): void => {
    this.withMappedAdapter(event.callId, "mute", (adapter, context) =>
      adapter.setMuted?.(context, event.isMuted),
    );
  };

  onHoldChanged = (event: HoldChangedEvent): void => {
    this.withMappedAdapter(event.callId, "hold", (adapter, context) =>
      adapter.setOnHold?.(context, event.isOnHold),
    );
  };

  onDtmf = (event: DtmfEvent): void => {
    this.withMappedAdapter(event.callId, "dtmf", (adapter, context) =>
      adapter.sendDtmf?.(context, event.digits),
    );
  };

  onAudioActivated = (): void => {
    if (!this.activeCallId) return;
    this.withMappedAdapter(
      this.activeCallId,
      "activateAudio",
      (adapter, context) => {
        this.audioActive = true;
        return adapter.activateAudio?.(context);
      },
    );
  };

  onAudioDeactivated = (): void => {
    if (!this.activeCallId || !this.audioActive) return;
    this.withMappedAdapter(
      this.activeCallId,
      "deactivateAudio",
      (adapter, context) => {
        this.audioActive = false;
        return adapter.deactivateAudio?.(context);
      },
    );
  };

  clear(): void {
    this.adapterByCall.clear();
    this.contextByCall.clear();
    this.preparationByCall.clear();
    this.answerByCall.clear();
    this.lifecycleTokenByCall.clear();
    this.activeCallId = null;
    this.audioActive = false;
  }

  private lifecycleToken(callId: string): object {
    let token = this.lifecycleTokenByCall.get(callId);
    if (!token) {
      token = {};
      this.lifecycleTokenByCall.set(callId, token);
    }
    return token;
  }

  private ownsLifecycle(
    callId: string,
    lifecycleToken: object,
    adapter: CallProviderAdapter,
  ): boolean {
    return (
      this.lifecycleTokenByCall.get(callId) === lifecycleToken &&
      this.adapterByCall.get(callId) === adapter
    );
  }

  private mergeContext(
    callId: string,
    next: CallProviderContext,
  ): CallProviderContext {
    const previous = this.contextByCall.get(callId);
    const merged: CallProviderContext = {
      ...previous,
      ...next,
      provider: next.provider ?? previous?.provider,
      serverCallId: next.serverCallId ?? previous?.serverCallId,
      payload: next.payload ?? previous?.payload,
      session: next.session ?? previous?.session,
      rawPushPayload: next.rawPushPayload ?? previous?.rawPushPayload,
      eventMeta: next.eventMeta ?? previous?.eventMeta,
    };
    this.contextByCall.set(callId, merged);
    return merged;
  }

  private resolve(context: CallProviderContext): CallProviderAdapter {
    const remembered = this.adapterByCall.get(context.callId);
    if (remembered) return remembered;
    // A canonical provider key is authoritative. Shape matching is a fallback
    // only for direct carrier pushes that cannot be changed by the app backend.
    const matches = context.provider
      ? this.adapters.filter((adapter) => adapter.id === context.provider)
      : this.adapters.filter((adapter) => adapter.matches?.(context) === true);
    if (matches.length === 0) {
      throw new CallProviderSelectionError(
        "ERR_NO_CALL_PROVIDER",
        `No call provider adapter matched call ${context.callId}.`,
      );
    }
    if (matches.length > 1) {
      throw new CallProviderSelectionError(
        "ERR_AMBIGUOUS_CALL_PROVIDER",
        `Multiple call provider adapters matched call ${context.callId}.`,
      );
    }
    this.adapterByCall.set(context.callId, matches[0]);
    return matches[0];
  }

  private withMappedAdapter(
    callId: string,
    phase: CallProviderPhase,
    operation: (
      adapter: CallProviderAdapter,
      context: CallProviderContext,
    ) => void | Promise<void> | undefined,
  ): void {
    const adapter = this.adapterByCall.get(callId);
    const context = this.contextByCall.get(callId);
    if (!adapter || !context) return;
    this.invoke(() => operation(adapter, context), phase, context, adapter);
  }

  private invoke(
    operation: () => void | Promise<void> | undefined,
    phase: CallProviderPhase,
    context: CallProviderContext,
    adapter: CallProviderAdapter,
  ): void {
    try {
      const result = operation();
      if (!result) return;
      Promise.resolve(result).catch((error) => {
        this.notify(error, phase, context, adapter);
      });
    } catch (error) {
      this.notify(error, phase, context, adapter);
    }
  }

  private notify(
    error: unknown,
    phase: CallProviderPhase,
    context?: CallProviderContext,
    adapter?: CallProviderAdapter,
  ): void {
    try {
      this.options.onError?.({
        error,
        phase,
        callId: context?.callId,
        adapterId: adapter?.id,
      });
    } catch {
      // Diagnostics must never prevent native answer failure/teardown. The
      // router also never logs because errors may originate beside raw push
      // signaling material owned by the consuming app.
    }
  }
}
