import { useCallback, useEffect, useRef, useState } from "react";
import { errorMessage } from "./api";

export interface Loaded<T> {
  data: T | undefined;
  error: string | undefined;
  loading: boolean;
  reload: () => void;
}

interface Result<T> {
  key: string;
  data: T | undefined;
  error: string | undefined;
}

export function useLoad<T>(key: string, load: () => Promise<T>): Loaded<T> {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Result<T>>({ key: "", data: undefined, error: undefined });
  const latestLoad = useRef(load);
  const requestKey = `${key}#${attempt}`;

  useEffect(() => {
    latestLoad.current = load;
  });

  useEffect(() => {
    let active = true;
    latestLoad.current().then(
      (data) => {
        if (active) setResult({ key: requestKey, data, error: undefined });
      },
      (failure: unknown) => {
        if (active) setResult((previous) => ({ key: requestKey, data: previous.data, error: errorMessage(failure) }));
      },
    );
    return () => {
      active = false;
    };
  }, [requestKey]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  const current = result.key === requestKey;
  return { data: result.data, error: current ? result.error : undefined, loading: !current, reload };
}
