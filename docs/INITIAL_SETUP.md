# Backend / Frontend 초기 구성

기준일: 2026-10-09 (Asia/Seoul). 초기 실행 기반과 DB·API·보안 연결을 구성한다. 초대·로그인·회원·팀·곡·예약·공지·Push 업무 기능은 후속 단계다.

## 구조와 버전

| 경로 | 구성 |
|---|---|
| `backend` | Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1 |
| `frontend` | Next.js 16.4.0, React 19.3.0, TypeScript 6.0.3, Tailwind 4.3.3, TanStack Query 5.104.1 |
| `compose.yaml` | 기존 PostgreSQL 17.11 개발 DB와 지속 볼륨 |
| `scripts` | DB 준비·검사, 두 앱 실행과 API 연결 검사 |
| `.github/workflows/ci.yaml` | 백엔드 PostgreSQL 통합 테스트·JAR, 프론트 타입·lint·테스트·build |

백엔드 의존성은 Boot BOM과 `gradle.lockfile`, 프론트는 정확한 버전과 `package-lock.json`으로 고정한다. 상세 버전과 공식 확인 출처는 [backend 안내](../backend/README.md), [frontend 안내](../frontend/README.md)를 따른다. Gradle 배포본과 Wrapper JAR은 앞선 환경 검증에서 확인한 같은 파일·SHA-256을 사용한다.

## 로컬 실행

Windows PowerShell에서 프로젝트 루트를 연다. 기존 개발환경은 유지하며 DB가 중지돼 있으면 다시 시작한다.

```powershell
docker desktop start --detach
./scripts/Initialize-DevEnvironment.ps1
docker compose up -d --wait --wait-timeout 90 postgres
```

첫 프론트 설치는 다음과 같다.

```powershell
Push-Location ./frontend
try { npm.cmd ci } finally { Pop-Location }
```

두 터미널에서 각각 실행한다.

```powershell
./scripts/Start-Backend.ps1
```

```powershell
./scripts/Start-Frontend.ps1
```

화면은 `http://127.0.0.1:3000`에서 확인한다. Next.js가 같은 origin의 `/api/*` 요청을 `http://127.0.0.1:8080`으로 전달한다. DB는 `127.0.0.1:5432`를 사용한다. 앱 포트 변경은 각 스크립트의 `-Port`, 프론트의 `-BackendPort`로 지정한다. 중지는 해당 터미널의 Ctrl+C다.

백엔드 스크립트는 기존 `.env`의 DB 이름·사용자·포트와 `secrets/postgres-password.txt`를 읽는다. 비밀번호를 명령 인수나 출력에 넣지 않고 프로세스 환경에만 전달한다. 종료 시 기존 프로세스 환경을 복원한다. 프론트의 `APP_API_ORIGIN`은 서버 전용 값이며 브라우저 공개 환경변수에 저장하지 않는다. 운영 rewrite 대상은 빌드 산출물에 반영되므로 운영 빌드 전에 지정한다.

## DB와 기본 보안

Flyway V1은 Spring Session JDBC의 공식 PostgreSQL 스키마를 바탕으로 두 세션 테이블을 생성한다. 첫 로컬 실행 때 Flyway 이력 테이블도 생성된다. 회원·팀·예약 테이블은 이후 새 migration으로 추가한다. 적용된 V1을 수정하거나 `ddl-auto=create/update`로 스키마를 바꾸지 않는다.

현재 공개 API는 `GET /api/v1/health`와 `GET /api/v1/auth/csrf`다. Health는 실제 DB 연결을 확인하고 최소 상태만 반환한다. CSRF는 세션 쿠키와 함께 사용할 마스킹된 토큰·헤더 이름을 반환한다. 두 응답은 `no-store`다. 기본 계정·임시 로그인 기능은 없으며 나머지 API는 기본 거부한다.

세션 쿠키는 HttpOnly·SameSite=Lax다. 기본 설정은 Secure이며 로컬 HTTP 개발 프로필에서만 Secure를 해제한다. 운영은 HTTPS에서 별도 환경변수로 실행한다. 세션 30분·DB pool 크기 같은 값은 초기 기술 설정이며 새로운 회원 운영 정책이 아니다.

프론트 공통 API client는 세션 쿠키·CSRF 헤더·공통 응답·오류 계약을 사용한다. 변경 요청은 자동 재시도하지 않고, 오프라인 요청은 즉시 실패해 연결 복구 때 자동 실행되지 않게 한다. 초기 화면은 제공된 정음 로고와 남색·노란색을 사용하는 준비 안내다.

## 검증 명령

백엔드 검사는 개발 DB와 분리된 실제 PostgreSQL Testcontainers를 사용한다.

```powershell
Push-Location ./backend
try {
    ./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon test bootJar
} finally { Pop-Location }
```

프론트 검사는 다음과 같다.

```powershell
Push-Location ./frontend
try {
    npm.cmd run typecheck
    npm.cmd run lint
    npm.cmd run test
    npm.cmd run build
} finally { Pop-Location }
```

두 앱 실행 후 Next.js 경유 DB·세션·CSRF·미인증 접근 거부를 검사한다. 토큰과 쿠키 값은 출력하지 않는다.

```powershell
./scripts/Test-ApplicationConnection.ps1
```

CI는 일반 Linux runner와 임시 DB를 사용하고 운영 secret·DB를 사용하지 않는다. 공식 Actions는 커밋 SHA로 고정하고 권한은 `contents: read`로 제한한다. CI 작성과 실제 GitHub 실행 성공은 구분해 결과를 기록한다.

## 결과와 다음 단계

2026-10-09 로컬 검증 결과:

- 백엔드: 실제 PostgreSQL Testcontainers 기반 HTTP·세션·CSRF·접근 거부 테스트 5건 통과, `bootJar` 성공. 개발 DB에서도 Flyway V1 적용과 앱 기동을 확인했다.
- 프론트: 타입 생성·검사, lint 오류·경고 0, API client·오프라인 변경 테스트 7건과 production build 통과. 의존성 peer 오류가 없고 런타임 `npm audit --omit=dev`는 0건이다.
- 실제 연결: Next.js를 통한 DB health, `no-store`, HttpOnly 세션 쿠키 전달, CSRF 누락 403과 유효 CSRF·미인증 요청 401을 확인했다.
- 화면: 넓은 화면과 모바일 폭에서 준비 안내·로고가 표시되고 가로 넘침이 없음을 확인했다. 실기기 PWA·Push·업무 화면 검증은 아직 수행하지 않았다.
- 보안: Git 추적·추가 후보에서 실제 DB 비밀번호·자격증명 marker·금지 경로가 없고, `.env`·`secrets`·빌드 결과·캐시가 제외됨을 확인했다.

개발 의존성 전체 감사에는 Next lint plugin의 `braces` 미패치 권고가 전파된 high 4개 패키지가 남아 있다. 동일한 권고 한 건이며 런타임 의존성에는 포함되지 않는다. 상세 경로와 대응은 [frontend 안내](../frontend/README.md)에 기록한다.

GitHub CI: 앱 코드 커밋 `fef700d`에서 [실행 37892024400](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/37892024400)이 성공했다. Linux runner에서 백엔드 테스트·JAR 빌드는 1분 42초, 프론트 설치·런타임 감사·타입·lint·테스트·build는 44초에 통과했다. 이 결과 기록 이후 변경은 문서뿐이며 앱 코드의 검증 대상은 위 커밋이다.

이후 초대·인증·회원·팀·곡의 기반을 구현하고 예약·반복 배정·변경·교환·공지·Push를 이어서 구현한다. PR 생성과 main 병합은 사용자 승인을 받은 뒤 진행한다.
