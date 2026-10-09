import { MutationObserver, onlineManager } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import { apiRequest } from "./api/client";
import { createQueryClient } from "./query-client";

it("rejects an offline mutation immediately and never resumes it after reconnecting", async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("navigator", { onLine: false });
  onlineManager.setOnline(false);
  const client = createQueryClient();
  client.mount();

  try {
    const mutationFn = vi.fn(() => apiRequest("/api/v1/example", { method: "POST" }));
    const observer = new MutationObserver(client, { mutationFn });

    await expect(observer.mutate()).rejects.toMatchObject({ code: "OFFLINE" });
    expect(observer.getCurrentResult()).toMatchObject({ status: "error", isPaused: false });

    vi.stubGlobal("navigator", { onLine: true });
    onlineManager.setOnline(true);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(mutationFn).toHaveBeenCalledOnce();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(observer.getCurrentResult().status).toBe("error");
  } finally {
    client.unmount();
    client.clear();
    onlineManager.setOnline(true);
    vi.unstubAllGlobals();
  }
});
