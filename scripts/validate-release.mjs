import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const packageJson = JSON.parse(
  readFileSync(new URL("../package.json", import.meta.url))
);
const changelog = readFileSync(
  new URL("../CHANGELOG.md", import.meta.url),
  "utf8"
);
const releaseTag = process.argv[2] ?? process.env.RELEASE_TAG;
const expectedTag = `v${packageJson.version}`;

function fail(message) {
  throw new Error(message);
}

if (!/^\d+\.\d+\.\d+$/.test(packageJson.version)) {
  fail(
    `Public releases require a stable semver version; received ${packageJson.version}`
  );
}
if (releaseTag !== expectedTag) {
  fail(
    `Release tag ${releaseTag ?? "<missing>"} must exactly match ${expectedTag}`
  );
}
if (!changelog.includes(`## ${packageJson.version}`)) {
  fail(
    `CHANGELOG.md must contain a release heading for ${packageJson.version}`
  );
}

const git = (...args) => execFileSync("git", args, { encoding: "utf8" }).trim();
const head = git("rev-parse", "HEAD");
const tagCommit = git("rev-list", "-n", "1", releaseTag);
if (tagCommit !== head) {
  fail(
    `Release tag ${releaseTag} points to ${tagCommit}, but checkout HEAD is ${head}`
  );
}

console.log(`Validated release ${releaseTag} at ${head}`);
