import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const packageJson = JSON.parse(
  readFileSync(new URL("../package.json", import.meta.url))
);
const androidBuild = readFileSync(
  new URL("../android/build.gradle", import.meta.url),
  "utf8"
);

const expectedMetadata = {
  name: "@ross-slaney/expo-callkit",
  repository: "git+https://github.com/ross-slaney/expo-callkit.git",
  registry: "https://registry.npmjs.org/",
  node: ">=20.19.4",
  expo: "^54.0.0",
  react: ">=19.1.0 <19.2.0",
  reactNative: ">=0.81.4 <0.82.0",
};

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

assert(
  packageJson.name === expectedMetadata.name,
  "Unexpected npm package name"
);
assert(
  packageJson.repository?.url === expectedMetadata.repository,
  "repository.url must match the public GitHub repository for provenance"
);
assert(
  packageJson.publishConfig?.registry === expectedMetadata.registry,
  "publishConfig.registry must target the public npm registry"
);
assert(
  packageJson.publishConfig?.access === "public",
  "Scoped package access must be public"
);
assert(
  packageJson.publishConfig?.provenance === true,
  "npm provenance must be enabled"
);
assert(
  packageJson.engines?.node === expectedMetadata.node,
  "Unexpected Node engine range"
);
assert(
  packageJson.peerDependencies?.expo === expectedMetadata.expo,
  "Unexpected Expo peer range"
);
assert(
  packageJson.peerDependencies?.react === expectedMetadata.react,
  "Unexpected React peer range"
);
assert(
  packageJson.peerDependencies?.["react-native"] ===
    expectedMetadata.reactNative,
  "Unexpected React Native peer range"
);
assert(
  androidBuild.includes("version = packageJson.version") &&
    androidBuild.includes("versionName packageJson.version"),
  "Android native metadata must derive its version from package.json"
);

const packOutput = execFileSync(
  process.platform === "win32" ? "npm.cmd" : "npm",
  ["pack", "--dry-run", "--json", "--ignore-scripts"],
  { encoding: "utf8" }
);
const pack = JSON.parse(packOutput)[0];
const files = new Set(pack.files.map(({ path }) => path));

const requiredFiles = [
  "app.plugin.js",
  "build/index.js",
  "build/index.d.ts",
  "expo-module.config.json",
  "plugin/build/index.js",
  "android/build.gradle",
  "android/src/main/AndroidManifest.xml",
  "android/src/main/java/dev/rossslaney/expocallkit/EventReplayPolicy.kt",
  "android/src/main/java/dev/rossslaney/expocallkit/ExpoCallKitModule.kt",
  "ios/CallRegistryCore/SingleCallRegistry.swift",
  "ios/EventCore/EventContract.swift",
  "ios/ExpoCallKit.podspec",
  "ios/ExpoCallKitModule.swift",
  "ios/PendingAnswersCore/PendingAnswers.swift",
  "LICENSE",
  "README.md",
];

for (const path of requiredFiles) {
  assert(files.has(path), `Packed artifact is missing ${path}`);
}

const forbidden = [...files].filter(
  (path) =>
    path.startsWith(".github/") ||
    path.startsWith("example/") ||
    path.startsWith("native-tests/") ||
    path.startsWith("plugin/src/") ||
    path.startsWith("scripts/") ||
    path.endsWith(".tsbuildinfo") ||
    path.includes("__tests__") ||
    path.includes(".eslintrc")
);

assert(
  forbidden.length === 0,
  `Packed artifact contains development files: ${forbidden.join(", ")}`
);
assert(
  pack.unpackedSize < 500_000,
  `Packed artifact is unexpectedly large: ${pack.unpackedSize} bytes`
);

console.log(
  `Validated ${packageJson.name}@${packageJson.version}: ${pack.entryCount} files, ${pack.unpackedSize} bytes unpacked`
);
