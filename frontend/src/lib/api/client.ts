export interface ApiMeta {
  serverTime: string;
  timeZone: "Asia/Seoul";
  nextCursor?: string | null;
}

export interface ApiResponse<T> {
  data: T;
  meta: ApiMeta;
}

export interface ApiErrorDetails {
  fieldErrors?: unknown[];
  conflicts?: unknown[];
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
    public readonly details: ApiErrorDetails = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const accessErrorListeners = new Set<(error: ApiError) => void>();

export function onApiAccessError(listener: (error: ApiError) => void): () => void {
  accessErrorListeners.add(listener);
  return () => { accessErrorListeners.delete(listener); };
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: HeadersInit;
  signal?: AbortSignal;
}

interface CsrfToken {
  headerName: string;
  token: string;
}

interface CsrfFlight {
  epoch: number;
  controller: AbortController;
  promise: Promise<CsrfToken>;
  waiters: number;
}

let sessionEpoch = 0;
let csrfFlight: CsrfFlight | null = null;
let sessionTransitionEpoch: number | null = null;

/** Discard pending preparation and responses when the browser's account/session changes. */
export function resetApiSession(): void {
  sessionEpoch += 1;
  const pending = csrfFlight;
  csrfFlight = null;
  pending?.controller.abort();
}

function checkCurrent(epoch: number, signal?: AbortSignal): void {
  signal?.throwIfAborted();
  if (epoch !== sessionEpoch) throw new DOMException("The session changed.", "AbortError");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

async function readResponse<T>(response: Response, signal?: AbortSignal, epoch = sessionEpoch): Promise<ApiResponse<T> | null> {
  checkCurrent(epoch, signal);
  if (response.status === 204) return null;

  const payload: unknown = await response.json().catch(() => { checkCurrent(epoch, signal); return null; });
  checkCurrent(epoch, signal);

  if (!response.ok) {
    const error = isRecord(payload) && isRecord(payload.error) ? payload.error : null;
    const failure = new ApiError(
      response.status,
      typeof error?.code === "string" ? error.code : "REQUEST_FAILED",
      typeof error?.message === "string" ? error.message : "요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.",
      typeof error?.requestId === "string" ? error.requestId : undefined,
      {
        fieldErrors: Array.isArray(error?.fieldErrors) ? error.fieldErrors : undefined,
        conflicts: Array.isArray(error?.conflicts) ? error.conflicts : undefined,
      },
    );
    if (failure.status === 401 || failure.status === 403) {
      checkCurrent(epoch, signal);
      accessErrorListeners.forEach((listener) => listener(failure));
    }
    throw failure;
  }

  if (
    !isRecord(payload) ||
    !("data" in payload) ||
    !isRecord(payload.meta) ||
    typeof payload.meta.serverTime !== "string" ||
    payload.meta.timeZone !== "Asia/Seoul"
  ) {
    throw new ApiError(response.status, "INVALID_RESPONSE", "응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.");
  }

  return payload as unknown as ApiResponse<T>;
}

async function fetchCsrfToken(signal: AbortSignal, epoch: number): Promise<CsrfToken> {
  const response = await fetch("/api/v1/auth/csrf", {
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    headers: { Accept: "application/json" },
    signal,
  });
  const result = await readResponse<CsrfToken>(response, signal, epoch);

  if (
    !isRecord(result?.data) ||
    typeof result.data.headerName !== "string" ||
    typeof result.data.token !== "string" ||
    !result.data.headerName ||
    !result.data.token
  ) {
    throw new ApiError(response.status, "INVALID_CSRF_RESPONSE", "요청을 준비하지 못했어요. 다시 시도해 주세요.");
  }

  return result.data;
}

function csrfToken(epoch: number, signal?: AbortSignal): Promise<CsrfToken> {
  checkCurrent(epoch, signal);
  let flight = csrfFlight;
  if (!flight || flight.epoch !== epoch) {
    const controller = new AbortController();
    flight = { epoch, controller, promise: fetchCsrfToken(controller.signal, epoch), waiters: 0 };
    csrfFlight = flight;
    const created = flight;
    flight.promise = flight.promise.finally(() => { if (csrfFlight === created) csrfFlight = null; });
  }
  const shared = flight;
  shared.waiters += 1;
  // One canceled screen must not cancel another screen's request using the same preparation.
  return new Promise<CsrfToken>((resolve, reject) => {
    let settled = false;
    const finish = (action: () => void) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener("abort", abort);
      shared.waiters -= 1;
      if (shared.waiters === 0 && csrfFlight === shared) {
        csrfFlight = null;
        shared.controller.abort();
      }
      action();
    };
    const abort = () => finish(() => reject(signal?.reason ?? new DOMException("Request canceled.", "AbortError")));
    signal?.addEventListener("abort", abort, { once: true });
    shared.promise.then(
      (token) => finish(() => {
        try { checkCurrent(epoch, signal); resolve(token); } catch (error) { reject(error); }
      }),
      (error: unknown) => finish(() => reject(error)),
    );
  });
}

/** Browser requests use the same origin; Spring remains the authority for permissions and CSRF. */
export async function apiRequest<T>(
  path: `/api/v1/${string}`,
  options: RequestOptions = {},
): Promise<ApiResponse<T> | null> {
  if (!path.startsWith("/api/v1/") || /[\\\r\n]/.test(path)) {
    throw new TypeError("API requests must use a relative /api/v1/ path.");
  }

  const method = options.method ?? "GET";
  if (method === "GET" && options.body !== undefined) {
    throw new TypeError("GET requests cannot include a body.");
  }

  if (method !== "GET" && typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new ApiError(0, "OFFLINE", "인터넷 연결을 확인한 뒤 다시 요청해 주세요.");
  }

  options.signal?.throwIfAborted();
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  const rotatesSession = method === "POST" && (path === "/api/v1/auth/login" || path === "/api/v1/auth/logout");
  if (method !== "GET" && sessionTransitionEpoch !== null) {
    throw new DOMException("Authentication is changing. Request again after it completes.", "AbortError");
  }
  if (rotatesSession) resetApiSession();
  const epoch = sessionEpoch;
  checkCurrent(epoch, options.signal);
  if (rotatesSession) sessionTransitionEpoch = epoch;

  try {
    if (method !== "GET") {
      // Only concurrent preparation is shared. Sequential writes always fetch the current token.
      const csrf = await csrfToken(epoch, options.signal);
      checkCurrent(epoch, options.signal);
      headers.set(csrf.headerName, csrf.token);
    }

    if (options.body !== undefined) headers.set("Content-Type", "application/json");

    const response = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });

    // Mutations are never replayed automatically, including a CSRF rejection or network failure.
    return await readResponse<T>(response, options.signal, epoch);
  } finally {
    // A lost login/logout response may still have rotated or deleted the server session.
    if (rotatesSession && sessionTransitionEpoch === epoch) sessionTransitionEpoch = null;
    if (rotatesSession && epoch === sessionEpoch) resetApiSession();
  }
}
