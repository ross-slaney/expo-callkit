import {
  CallKitValidationError,
  assertUuid,
  isCallEndReason,
  isUuidString,
  normalizeIncomingCallPayload,
  normalizeParticipant,
} from "../payload";

describe("normalizeIncomingCallPayload", () => {
  const valid = {
    eventId: "evt-1",
    serverCallId: "srv-1",
    caller: { id: "user-9", displayName: "Jane Doe" },
  };

  it("accepts a minimal valid payload", () => {
    expect(normalizeIncomingCallPayload(valid)).toEqual({
      eventId: "evt-1",
      serverCallId: "srv-1",
      caller: { id: "user-9", displayName: "Jane Doe" },
    });
  });

  it("carries hasVideo and metadata through untouched", () => {
    const metadata = { bookingId: 42, nested: { a: [1, 2, 3] } };
    const result = normalizeIncomingCallPayload({
      ...valid,
      hasVideo: true,
      metadata,
    });
    expect(result.hasVideo).toBe(true);
    expect(result.metadata).toEqual(metadata);
  });

  it("normalizes an explicit provider key for multi-provider apps", () => {
    expect(
      normalizeIncomingCallPayload({ ...valid, provider: "  telnyx  " }),
    ).toMatchObject({ provider: "telnyx" });
  });

  it("trims string fields and drops empty optionals", () => {
    const result = normalizeIncomingCallPayload({
      eventId: "  evt-2  ",
      serverCallId: " srv-2 ",
      caller: { id: " user-1 ", displayName: "   ", phoneNumber: "+15551234" },
    });
    expect(result.eventId).toBe("evt-2");
    expect(result.serverCallId).toBe("srv-2");
    expect(result.caller).toEqual({ id: "user-1", phoneNumber: "+15551234" });
  });

  it.each([
    ["not an object", null],
    ["missing eventId", { serverCallId: "s", caller: { id: "c" } }],
    ["empty eventId", { eventId: " ", serverCallId: "s", caller: { id: "c" } }],
    ["missing serverCallId", { eventId: "e", caller: { id: "c" } }],
    ["missing caller", { eventId: "e", serverCallId: "s" }],
    ["caller without id", { eventId: "e", serverCallId: "s", caller: {} }],
    [
      "non-boolean hasVideo",
      { eventId: "e", serverCallId: "s", caller: { id: "c" }, hasVideo: "yes" },
    ],
    [
      "array metadata",
      { eventId: "e", serverCallId: "s", caller: { id: "c" }, metadata: [1] },
    ],
    [
      "non-string provider",
      { eventId: "e", serverCallId: "s", caller: { id: "c" }, provider: 1 },
    ],
  ])("rejects %s", (_label, input) => {
    expect(() => normalizeIncomingCallPayload(input)).toThrow(
      CallKitValidationError,
    );
  });

  it("tags validation errors with ERR_INVALID_ARGUMENT", () => {
    try {
      normalizeIncomingCallPayload(null);
      throw new Error("expected throw");
    } catch (error) {
      expect((error as CallKitValidationError).code).toBe(
        "ERR_INVALID_ARGUMENT",
      );
    }
  });
});

describe("normalizeParticipant", () => {
  it("rejects non-string handles", () => {
    expect(() =>
      normalizeParticipant({ id: "a", phoneNumber: 5551234 }),
    ).toThrow(CallKitValidationError);
  });

  it("keeps all provided handles", () => {
    expect(
      normalizeParticipant({
        id: "a",
        displayName: "A",
        phoneNumber: "+1555",
        email: "a@b.c",
      }),
    ).toEqual({
      id: "a",
      displayName: "A",
      phoneNumber: "+1555",
      email: "a@b.c",
    });
  });
});

describe("isCallEndReason", () => {
  it.each([
    "failed",
    "remoteEnded",
    "unanswered",
    "answeredElsewhere",
    "declinedElsewhere",
    "local",
    "unknown",
  ])("accepts %s", (reason) => {
    expect(isCallEndReason(reason)).toBe(true);
  });

  it.each(["", "REMOTE_ENDED", "busy", 3, null, undefined])(
    "rejects %p",
    (value) => {
      expect(isCallEndReason(value)).toBe(false);
    },
  );
});

describe("uuid helpers", () => {
  it("accepts canonical UUIDs in any case", () => {
    expect(isUuidString("9b2f8c44-6f0f-4a1e-9d5e-1c2b3a4d5e6f")).toBe(true);
    expect(isUuidString("9B2F8C44-6F0F-4A1E-9D5E-1C2B3A4D5E6F")).toBe(true);
  });

  it("rejects non-UUID strings", () => {
    expect(isUuidString("not-a-uuid")).toBe(false);
    expect(isUuidString("")).toBe(false);
  });

  it("assertUuid returns the value or throws", () => {
    const id = "9b2f8c44-6f0f-4a1e-9d5e-1c2b3a4d5e6f";
    expect(assertUuid(id, "callId")).toBe(id);
    expect(() => assertUuid("nope", "callId")).toThrow(CallKitValidationError);
    expect(() => assertUuid(12, "callId")).toThrow(CallKitValidationError);
  });
});
