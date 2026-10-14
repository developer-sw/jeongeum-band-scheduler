import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest, onApiAccessError, resetApiSession } from "./client";

const meta = { serverTime: "2026-10-09T14:00:00+09:00", timeZone: "Asia/Seoul" };
const json = (payload: unknown, status = 200) => Response.json(payload, { status });

afterEach(() => { resetApiSession(); vi.unstubAllGlobals(); });

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

describe("same-origin API requests", () => {
  it("shares one initial CSRF fetch across simultaneous mutations but fetches afresh for the next write", async () => {
    const firstToken = deferred<Response>();
    const fetchMock = vi.fn().mockImplementation((path: string) => {
      if (path === "/api/v1/auth/csrf") return firstToken.promise;
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    vi.stubGlobal("fetch", fetchMock);
    const first = apiRequest("/api/v1/example/one", { method: "POST" });
    const second = apiRequest("/api/v1/example/two", { method: "PATCH" });
    expect(fetchMock).toHaveBeenCalledOnce();
    firstToken.resolve(json({ data: { headerName: "X-CSRF-TOKEN", token: "shared" }, meta }));
    await Promise.all([first, second]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("X-CSRF-TOKEN")).toBe("shared");
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get("X-CSRF-TOKEN")).toBe("shared");
    fetchMock.mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "fresh" }, meta }));
    await apiRequest("/api/v1/example/three", { method: "DELETE" });
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/v1/auth/csrf")).toHaveLength(2);
    expect(new Headers(fetchMock.mock.calls[4][1].headers).get("X-CSRF-TOKEN")).toBe("fresh");
  });

  it("isolates one caller's cancellation while another caller still needs the shared CSRF fetch", async () => {
    const token = deferred<Response>();
    const fetchMock = vi.fn().mockImplementation((path: string) => path === "/api/v1/auth/csrf"
      ? token.promise : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", fetchMock);
    const canceled = new AbortController();
    const first = apiRequest("/api/v1/example/canceled", { method: "POST", signal: canceled.signal });
    const rejection = expect(first).rejects.toMatchObject({ name: "AbortError" });
    const second = apiRequest("/api/v1/example/current", { method: "POST" });
    const sharedSignal = fetchMock.mock.calls[0][1].signal as AbortSignal;
    canceled.abort();
    await rejection;
    expect(sharedSignal.aborted).toBe(false);
    token.resolve(json({ data: { headerName: "X-CSRF-TOKEN", token: "shared" }, meta }));
    await second;
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/v1/auth/csrf", "/api/v1/example/current"]);
  });

  it("aborts unused CSRF preparation and never attaches its late token to a later write", async () => {
    const stale = deferred<Response>();
    const fetchMock = vi.fn()
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "current" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const canceled = new AbortController();
    const first = apiRequest("/api/v1/example/canceled", { method: "POST", signal: canceled.signal });
    const rejection = expect(first).rejects.toMatchObject({ name: "AbortError" });
    const sharedSignal = fetchMock.mock.calls[0][1].signal as AbortSignal;
    canceled.abort();
    await rejection;
    expect(sharedSignal.aborted).toBe(true);
    await apiRequest("/api/v1/example/current", { method: "POST" });
    stale.resolve(json({ data: { headerName: "X-CSRF-TOKEN", token: "stale" }, meta }));
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get("X-CSRF-TOKEN")).toBe("current");
  });

  it("does not send an old prepared mutation after login rotates the session", async () => {
    const stale = deferred<Response>();
    const fetchMock = vi.fn()
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "login" }, meta }))
      .mockResolvedValueOnce(json({ data: { id: "new-member" }, meta }))
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "new-session" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const old = apiRequest("/api/v1/example/old", { method: "POST" });
    const rejection = expect(old).rejects.toMatchObject({ name: "AbortError" });
    await apiRequest("/api/v1/auth/login", { method: "POST", body: { loginId: "fixture" } });
    stale.resolve(json({ data: { headerName: "X-CSRF-TOKEN", token: "old-session" }, meta }));
    await rejection;
    await apiRequest("/api/v1/example/new", { method: "POST" });
    expect(fetchMock.mock.calls.map(([path]) => path)).not.toContain("/api/v1/example/old");
    expect(new Headers(fetchMock.mock.calls[4][1].headers).get("X-CSRF-TOKEN")).toBe("new-session");
  });

  it("suppresses a late old-account access error when the session epoch changed without a caller signal", async () => {
    const stale = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn().mockReturnValueOnce(stale.promise));
    const listener = vi.fn();
    const unsubscribe = onApiAccessError(listener);
    try {
      const request = apiRequest("/api/v1/auth/me");
      const rejection = expect(request).rejects.toMatchObject({ name: "AbortError" });
      resetApiSession();
      stale.resolve(json({ error: { code: "AUTHENTICATION_REQUIRED", message: "" } }, 401));
      await rejection;
      expect(listener).not.toHaveBeenCalled();
    } finally { unsubscribe(); }
  });

  it("rejects new writes during login instead of sending them under a changing account or queueing them", async () => {
    const loginResponse = deferred<Response>();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "login" }, meta }))
      .mockReturnValueOnce(loginResponse.promise)
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "new-session" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const login = apiRequest("/api/v1/auth/login", { method: "POST", body: { loginId: "fixture" } });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await expect(apiRequest("/api/v1/example", { method: "POST" })).rejects.toMatchObject({ name: "AbortError" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    loginResponse.resolve(json({ data: { id: "new-member" }, meta }));
    await login;
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await apiRequest("/api/v1/example", { method: "POST" });
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("requires explicit retry after CSRF preparation failure and does not retain a rejected shared promise", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("Network unavailable"))
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "retry" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const first = apiRequest("/api/v1/example", { method: "POST" });
    const second = apiRequest("/api/v1/example", { method: "POST" });
    const results = await Promise.allSettled([first, second]);
    expect(results.map((result) => result.status)).toEqual(["rejected", "rejected"]);
    expect(fetchMock).toHaveBeenCalledOnce();
    await apiRequest("/api/v1/example", { method: "POST" });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("does not invalidate the new account when an old 401 response is canceled during JSON parsing", async () => {
    let finish!: (value: unknown) => void;
    const delayed = new Promise((resolve) => { finish = resolve; });
    const bodyStarted = vi.fn(() => delayed);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 401, ok: false, json: bodyStarted }));
    const controller = new AbortController();
    const accessListener = vi.fn(); const unsubscribe = onApiAccessError(accessListener);
    try {
      const pending = apiRequest("/api/v1/auth/me", { signal: controller.signal });
      await vi.waitFor(() => expect(bodyStarted).toHaveBeenCalledOnce());
      controller.abort();
      finish({ error: { code: "AUTHENTICATION_REQUIRED", message: "" } });
      await expect(pending).rejects.toMatchObject({ name: "AbortError" });
      expect(accessListener).not.toHaveBeenCalled();
    } finally { unsubscribe(); }
  });
  it("reads typed data and server time without fetching a CSRF token for GET", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ data: { status: "UP" }, meta }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiRequest<{ status: string }>("/api/v1/health");

    expect(result).toEqual({ data: { status: "UP" }, meta });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/health", expect.objectContaining({
      credentials: "same-origin", cache: "no-store", redirect: "error", method: "GET",
    }));
  });

  it("uses the current session CSRF token on every mutation, including after session rotation", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "first-token" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "rotated-token" }, meta }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("/api/v1/auth/logout", { method: "POST" });
    await apiRequest("/api/v1/example", { method: "PATCH", body: { expectedVersion: 1 } });

    expect(fetchMock).toHaveBeenCalledTimes(4);
    const first = fetchMock.mock.calls[1][1] as RequestInit;
    const second = fetchMock.mock.calls[3][1] as RequestInit;
    expect(new Headers(first.headers).get("X-CSRF-TOKEN")).toBe("first-token");
    expect(new Headers(second.headers).get("X-CSRF-TOKEN")).toBe("rotated-token");
    expect(new Headers(second.headers).get("Content-Type")).toBe("application/json");
    expect(second.body).toBe('{"expectedVersion":1}');
    expect(second.credentials).toBe("same-origin");
  });

  it("does not send a mutation when obtaining a CSRF token fails", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ error: { code: "FORBIDDEN", message: "접근할 수 없어요." } }, 403));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("/api/v1/example", { method: "DELETE" })).rejects.toMatchObject({ status: 403, code: "FORBIDDEN" });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("preserves API conflict details and never replays a failed mutation", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ data: { headerName: "X-CSRF-TOKEN", token: "csrf" }, meta }))
      .mockResolvedValueOnce(json({ error: {
        code: "VERSION_CONFLICT", message: "최신 상태를 확인해 주세요.", requestId: "request-id",
        conflicts: [{ date: "2026-10-09", blockCode: "B01", instrumentCodes: ["DRUMS"] }],
      } }, 409));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("/api/v1/example", { method: "PUT", body: { expectedVersion: 1 } })).rejects.toMatchObject({
      status: 409, code: "VERSION_CONFLICT", requestId: "request-id",
      details: { conflicts: [{ date: "2026-10-09", blockCode: "B01", instrumentCodes: ["DRUMS"] }] },
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("does not expose raw proxy error pages to the caller", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>upstream internals</html>", { status: 502 })));

    const error = await apiRequest("/api/v1/health").catch((value: unknown) => value);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("REQUEST_FAILED");
    expect((error as ApiError).message).not.toContain("upstream");
  });

  it("rejects external URLs before sending cookies or a CSRF token", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("https://example.com/api/v1/example" as `/api/v1/${string}`)).rejects.toBeInstanceOf(TypeError);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
