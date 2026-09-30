import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";

export interface Spec extends TurboModule {
  call(pluginId: string, method: string, payload: string): Promise<string>;
}

export default TurboModuleRegistry.getEnforcing<Spec>("NativePluginHost");
