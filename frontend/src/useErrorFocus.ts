import { useCallback, useEffect, useRef, useState } from "react";

export function useErrorFocus() {
  const formRef = useRef<HTMLFormElement>(null);
  const messageRef = useRef<HTMLDivElement>(null);
  const [failures, setFailures] = useState(0);

  useEffect(() => {
    if (failures === 0) return;
    const invalid = formRef.current?.querySelector<HTMLElement>("[aria-invalid='true']");
    (invalid ?? messageRef.current)?.focus();
  }, [failures]);

  const failed = useCallback(() => setFailures((count) => count + 1), []);
  return { formRef, messageRef, failed };
}
