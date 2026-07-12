import {
  ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withEntitlementsPlist,
  withInfoPlist,
} from "expo/config-plugins";

import {
  AndroidManifestShape,
  ExpoCallKitPluginProps,
  applyAndroidManifestEntries,
  applyEntitlementEntries,
  applyInfoPlistEntries,
  resolveProps,
} from "./config";

const pkg: { name: string; version: string } = require("../../package.json");

const withExpoCallKit: ConfigPlugin<ExpoCallKitPluginProps | undefined> = (
  config,
  props,
) => {
  const resolved = resolveProps(props ?? undefined);

  config = withInfoPlist(config, (mod) => {
    mod.modResults = applyInfoPlistEntries(
      mod.modResults,
      resolved,
    ) as typeof mod.modResults;
    return mod;
  });

  config = withEntitlementsPlist(config, (mod) => {
    mod.modResults = applyEntitlementEntries(
      mod.modResults,
    ) as typeof mod.modResults;
    return mod;
  });

  config = withAndroidManifest(config, (mod) => {
    mod.modResults = applyAndroidManifestEntries(
      mod.modResults as unknown as AndroidManifestShape,
      resolved,
    ) as unknown as typeof mod.modResults;
    return mod;
  });

  return config;
};

export type { ExpoCallKitPluginProps };

export default createRunOncePlugin(withExpoCallKit, pkg.name, pkg.version);
