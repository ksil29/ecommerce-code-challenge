import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useLoad } from "./useLoad";

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("useLoad", () => {
  it("ignores an answer that arrives after the request changed", async () => {
    const answers = { old: deferred<string>(), current: deferred<string>() };
    const { result, rerender } = renderHook(({ query }: { query: keyof typeof answers }) => useLoad(query, () => answers[query].promise), {
      initialProps: { query: "old" as keyof typeof answers },
    });

    rerender({ query: "current" });
    await act(async () => answers.current.resolve("current results"));
    await act(async () => answers.old.resolve("old results"));

    expect(result.current).toMatchObject({ data: "current results", loading: false });
  });

  it("keeps the last data when a reload fails and recovers on the next one", async () => {
    const load = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("first")
      .mockRejectedValueOnce(new Error("Network down"))
      .mockResolvedValueOnce("third");
    const { result } = renderHook(() => useLoad("orders", load));
    await waitFor(() => expect(result.current.data).toBe("first"));

    act(() => result.current.reload());
    await waitFor(() => expect(result.current.error).toBe("Network down"));
    expect(result.current.data).toBe("first");

    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe("third"));
    expect(result.current.error).toBeUndefined();
  });
});
