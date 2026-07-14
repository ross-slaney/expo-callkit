import { execFileSync } from "node:child_process";

/**
 * npm 10 can prefix `npm pack --json` with direct stdout from a prepare
 * script. Parse the final JSON array instead of assuming stdout is pure JSON.
 */
export function parseNpmPackJson(output) {
  const lines = output.split(/\r?\n/);
  for (let start = lines.length - 1; start >= 0; start -= 1) {
    if (!lines[start].trimStart().startsWith("[")) continue;
    for (let end = lines.length; end > start; end -= 1) {
      if (!lines[end - 1].trimEnd().endsWith("]")) continue;
      try {
        const parsed = JSON.parse(lines.slice(start, end).join("\n"));
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      } catch {
        // A lifecycle log line can also start with '['; try the next span.
      }
    }
  }
  throw new Error("npm pack did not produce a parseable JSON artifact report");
}

export function runNpmPack(args = []) {
  const output = execFileSync(
    process.platform === "win32" ? "npm.cmd" : "npm",
    ["pack", ...args, "--json"],
    { encoding: "utf8" },
  );
  return parseNpmPackJson(output);
}
