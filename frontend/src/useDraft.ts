import { useState } from "react";

export function useDraft<T>(applied: T, appliedKey: string) {
  const [draft, setDraft] = useState(applied);
  const [draftKey, setDraftKey] = useState(appliedKey);
  if (draftKey !== appliedKey) {
    setDraftKey(appliedKey);
    setDraft(applied);
  }
  return [draft, setDraft] as const;
}
