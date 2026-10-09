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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

async function readResponse<T>(response: Response, signal?: AbortSignal): Promise<ApiResponse<T> | null> {
  signal?.throwIfAborted();
  if (response.status === 204) return null;

  const payload: unknown = await response.json().catch(() => { signal?.throwIfAborted(); return null; });
  signal?.throwIfAborted();

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
      signal?.throwIfAborted();
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

async function csrfToken(signal?: AbortSignal): Promise<CsrfToken> {
  const response = await fetch("/api/v1/auth/csrf", {
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    headers: { Accept: "application/json" },
    signal,
  });
  const result = await readResponse<CsrfToken>(response, signal);

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

  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");

  if (method !== "GET") {
    // Fetch anew for each mutation so login/logout session rotation cannot retain an old token.
    const csrf = await csrfToken(options.signal);
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
  return readResponse<T>(response, options.signal);
}
