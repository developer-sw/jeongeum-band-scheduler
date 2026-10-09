param(
    [ValidateRange(1024, 65535)]
    [int]$FrontendPort = 3000
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$baseUri = [Uri]"http://127.0.0.1:$FrontendPort"
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)

function Send-ApplicationProbe([string]$Method, [string]$Path, [hashtable]$Headers = @{}) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method), [Uri]::new($baseUri, $Path))
    $response = $null
    try {
        foreach ($name in $Headers.Keys) {
            $request.Headers.Add($name, $Headers[$name])
        }
        if ($Method -eq 'POST') {
            $request.Content = New-Object System.Net.Http.StringContent('{}', [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        return [PSCustomObject]@{
            Status = [int]$response.StatusCode
            Body = $body
            NoStore = $response.Headers.CacheControl.NoStore
        }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

try {
    $health = Send-ApplicationProbe 'GET' '/api/v1/health'
    if ($health.Status -ne 200 -or $health.Body.data.status -ne 'UP' -or -not $health.NoStore -or $health.Body.meta.timeZone -ne 'Asia/Seoul') {
        throw 'Frontend-to-backend health or DB connection check failed.'
    }

    $csrf = Send-ApplicationProbe 'GET' '/api/v1/auth/csrf'
    if ($csrf.Status -ne 200 -or $csrf.Body.data.headerName -ne 'X-CSRF-TOKEN' -or [string]::IsNullOrWhiteSpace($csrf.Body.data.token) -or -not $csrf.NoStore) {
        throw 'The CSRF endpoint did not return the expected contract.'
    }
    $sessionCookie = $handler.CookieContainer.GetCookies($baseUri) | Where-Object Name -eq 'SESSION'
    if (-not $sessionCookie -or -not $sessionCookie.HttpOnly) {
        throw 'The HttpOnly session cookie was not forwarded through Next.js.'
    }

    $missingCsrf = Send-ApplicationProbe 'POST' '/api/v1/reservations'
    if ($missingCsrf.Status -ne 403 -or $missingCsrf.Body.error.code -ne 'CSRF_INVALID') {
        throw 'A mutation without CSRF was not rejected correctly.'
    }
    $csrfHeaders = @{ 'X-CSRF-TOKEN' = $csrf.Body.data.token }
    $authenticatedOnly = Send-ApplicationProbe 'POST' '/api/v1/reservations' $csrfHeaders
    if ($authenticatedOnly.Status -ne 401 -or $authenticatedOnly.Body.error.code -ne 'AUTHENTICATION_REQUIRED') {
        throw 'The session-bound CSRF token was not accepted, or anonymous access was not rejected.'
    }
    Write-Output 'PASS: Next.js API forwarding, DB health, no-store, HttpOnly session and CSRF/authentication checks.'
} finally {
    $client.Dispose()
    $handler.Dispose()
}
