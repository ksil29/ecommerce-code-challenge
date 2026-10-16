import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api } from "./api";
import type { Settings } from "./types";

export const DEFAULT_SETTINGS: Settings = {
  maxQuantity: 99,
  paymentTimeoutSeconds: 900,
  reconcileIntervalSeconds: 60,
  passwordMinLength: 12,
  passwordMaxLength: 128,
  apiDocs: false,
};

const SettingsContext = createContext<Settings>(DEFAULT_SETTINGS);

function positiveWhole(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : fallback;
}

function withDefaults(loaded: Partial<Settings> | null): Settings {
  return {
    maxQuantity: positiveWhole(loaded?.maxQuantity, DEFAULT_SETTINGS.maxQuantity),
    paymentTimeoutSeconds: positiveWhole(loaded?.paymentTimeoutSeconds, DEFAULT_SETTINGS.paymentTimeoutSeconds),
    reconcileIntervalSeconds: positiveWhole(loaded?.reconcileIntervalSeconds, DEFAULT_SETTINGS.reconcileIntervalSeconds),
    passwordMinLength: positiveWhole(loaded?.passwordMinLength, DEFAULT_SETTINGS.passwordMinLength),
    passwordMaxLength: positiveWhole(loaded?.passwordMaxLength, DEFAULT_SETTINGS.passwordMaxLength),
    apiDocs: loaded?.apiDocs === true,
  };
}

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState(DEFAULT_SETTINGS);

  useEffect(() => {
    let active = true;
    api
      .settings()
      .then((loaded) => {
        if (active) setSettings(withDefaults(loaded));
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);

  return <SettingsContext.Provider value={settings}>{children}</SettingsContext.Provider>;
}

export function useSettings(): Settings {
  return useContext(SettingsContext);
}
