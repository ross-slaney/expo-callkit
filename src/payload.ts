import type {
  CallEndReason,
  CallParticipant,
  IncomingCallPayload,
  OutgoingCallOptions,
} from "./ExpoCallKit.types";

const END_REASONS: readonly CallEndReason[] = [
  "failed",
  "remoteEnded",
  "unanswered",
  "answeredElsewhere",
  "declinedElsewhere",
  "local",
  "unknown",
];

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Error thrown when a payload or argument fails validation in JS. */
export class CallKitValidationError extends Error {
  readonly code = "ERR_INVALID_ARGUMENT";

  constructor(message: string) {
    super(message);
    this.name = "CallKitValidationError";
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function optionalString(
  source: Record<string, unknown>,
  key: string,
  context: string,
): string | undefined {
  const value = source[key];
  if (value == null) {
    return undefined;
  }
  if (typeof value !== "string") {
    throw new CallKitValidationError(`${context}.${key} must be a string`);
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function requiredString(
  source: Record<string, unknown>,
  key: string,
  context: string,
): string {
  const value = optionalString(source, key, context);
  if (value === undefined) {
    throw new CallKitValidationError(
      `${context}.${key} is required and must be a non-empty string`,
    );
  }
  return value;
}

/** True when `value` looks like a canonical UUID string. */
export function isUuidString(value: string): boolean {
  return UUID_PATTERN.test(value);
}

/** True when `value` is a known {@link CallEndReason}. */
export function isCallEndReason(value: unknown): value is CallEndReason {
  return (
    typeof value === "string" && END_REASONS.includes(value as CallEndReason)
  );
}

/**
 * Validates and normalizes a participant: trims strings, drops empty
 * optionals. Throws {@link CallKitValidationError} when invalid.
 */
export function normalizeParticipant(
  input: unknown,
  context = "participant",
): CallParticipant {
  if (!isPlainObject(input)) {
    throw new CallKitValidationError(`${context} must be an object`);
  }
  const participant: CallParticipant = {
    id: requiredString(input, "id", context),
  };
  const displayName = optionalString(input, "displayName", context);
  const phoneNumber = optionalString(input, "phoneNumber", context);
  const email = optionalString(input, "email", context);
  if (displayName !== undefined) participant.displayName = displayName;
  if (phoneNumber !== undefined) participant.phoneNumber = phoneNumber;
  if (email !== undefined) participant.email = email;
  return participant;
}

/**
 * Validates and normalizes an incoming-call payload before it crosses the
 * bridge. Mirrors the native parsers so that malformed payloads fail fast in
 * JS with a useful message instead of dying inside native code.
 */
export function normalizeIncomingCallPayload(
  input: unknown,
): IncomingCallPayload {
  if (!isPlainObject(input)) {
    throw new CallKitValidationError("incoming call payload must be an object");
  }
  const payload: IncomingCallPayload = {
    eventId: requiredString(input, "eventId", "payload"),
    serverCallId: requiredString(input, "serverCallId", "payload"),
    caller: normalizeParticipant(input.caller, "payload.caller"),
  };
  const provider = optionalString(input, "provider", "payload");
  if (provider !== undefined) payload.provider = provider;
  if (input.hasVideo !== undefined) {
    if (typeof input.hasVideo !== "boolean") {
      throw new CallKitValidationError("payload.hasVideo must be a boolean");
    }
    payload.hasVideo = input.hasVideo;
  }
  if (input.metadata !== undefined) {
    if (!isPlainObject(input.metadata)) {
      throw new CallKitValidationError("payload.metadata must be an object");
    }
    payload.metadata = input.metadata;
  }
  return payload;
}

/**
 * Validates and normalizes outgoing-call options before they cross the native
 * bridge. A canonical provider key is required by multi-provider apps so the
 * lifecycle router can bind system actions to the correct media adapter.
 */
export function normalizeOutgoingCallOptions(
  input: unknown = {},
): OutgoingCallOptions {
  if (!isPlainObject(input)) {
    throw new CallKitValidationError("outgoing call options must be an object");
  }
  const options: OutgoingCallOptions = {};
  const provider = optionalString(input, "provider", "options");
  if (provider !== undefined) options.provider = provider;
  if (input.hasVideo !== undefined) {
    if (typeof input.hasVideo !== "boolean") {
      throw new CallKitValidationError("options.hasVideo must be a boolean");
    }
    options.hasVideo = input.hasVideo;
  }
  if (input.metadata !== undefined) {
    if (!isPlainObject(input.metadata)) {
      throw new CallKitValidationError("options.metadata must be an object");
    }
    options.metadata = input.metadata;
  }
  return options;
}

/** Asserts `callId`/`requestId` style arguments are non-empty UUID strings. */
export function assertUuid(value: unknown, name: string): string {
  if (typeof value !== "string" || !isUuidString(value)) {
    throw new CallKitValidationError(`${name} must be a UUID string`);
  }
  return value;
}
