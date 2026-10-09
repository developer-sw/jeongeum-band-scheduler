import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "./client";

const meta = { serverTime: "2026-10-09T14:00:00+09:00", timeZone: "Asia/Seoul" };
const json = (payload: unknown, status = 200) => Response.json(payload, { status });

afterEach(() => vi.unstubAllGlobals());

describe("same-origin API requests", () => {
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
