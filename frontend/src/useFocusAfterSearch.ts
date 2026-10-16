import { useEffect, useRef } from "react";

export function useFocusAfterSearch(query: string) {
  const searchButton = useRef<HTMLButtonElement>(null);
  const shownQuery = useRef(query);

  useEffect(() => {
    if (shownQuery.current === query) return;
    shownQuery.current = query;
    if (document.activeElement === document.body) searchButton.current?.focus();
  }, [query]);

  return searchButton;
}
