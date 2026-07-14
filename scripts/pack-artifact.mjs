import { resolve } from "node:path";

import { runNpmPack } from "./npm-pack-json.mjs";

const destination = resolve(process.argv[2] ?? "/tmp");
const [artifact] = runNpmPack(["--pack-destination", destination]);

if (!artifact?.filename) {
  throw new Error("npm pack did not report an artifact filename");
}

process.stdout.write(resolve(destination, artifact.filename));
