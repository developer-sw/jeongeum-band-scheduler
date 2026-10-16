# 초대 가입·로그인·권한 기반

기준일: 2026-10-10 (Asia/Seoul). 개인별 일회용 초대, 가입·로그인·로그아웃·현재 회원 조회, 최초 전체 관리자 초기화 절차를 보존하고 사용자 승인에 따라 90일 로그인 유지와 관련 화면·검증을 추가한다. 회원·팀·곡 관리의 현재 구현은 [MEMBER_TEAM_MANAGEMENT](MEMBER_TEAM_MANAGEMENT.md)를 따른다. 예약·공지·Push 기능은 이후 단계다.

확정 권한은 [PROJECT_CONTEXT](PROJECT_CONTEXT.md)를 따른다. 전체 관리자와 특정 팀의 팀장 역할을 구분한다. 기본 가입 역할은 `MEMBER`다. **2026-10-16 사용자 확정:** 전체 관리자가 미가입 명단에 지정한 `signupGlobalRole`을 그 명단의 개인 초대 가입 때 적용한다. 공개 가입 본문의 역할·다른 명단 ID·이름으로 권한을 정하지 않는다. directory guard와 초대·명단 잠금 뒤 DB의 최신 지정 값을 읽어 계정 생성·초대 소비·연결을 한 트랜잭션으로 처리한다. 회원 상태·역할은 로그인 세션의 예전 정보만으로 판단하지 않는다.

## 구현 계약과 기술 설정

2026-10-14 회원 탈퇴 단계의 세션 해제·로그인 경합 보완은 [MEMBER_TEAM_LIFECYCLE](MEMBER_TEAM_LIFECYCLE.md)을 따른다. 비밀번호 검증 후 인증 세션을 저장하는 마지막 단계는 directory guard 안에서 현재 활성 회원을 다시 검사하고 CSRF/세션 ID 회전과 90일 설정을 유지한다. 탈퇴는 해당 회원의 모든 JDBC 세션을 업무 정리와 함께 삭제하며, 늦은 로그인·Push 재등록이 접근을 복구하지 못하도록 격리 검증한다. 다른 회원의 세션과 일반 로그아웃의 기기별 정책은 유지한다.

회원 ID는 bigint를 JSON 문자열로 전달한다. 인증 PR #3에서는 팀 관리 전이므로 `teamMemberships`를 빈 목록으로 반환했다. 이후 [회원·팀 관리 단계](MEMBER_TEAM_MANAGEMENT.md)에서는 연결된 명단의 현재 활성 소속을 `teamId/teamName/kind/role` 목록으로 반환하고 초대에 명단 ID를 연결한다. 팀장 권한을 전역 역할로 저장하지 않는다.

| 요청 | 결과 |
|---|---|
| `POST /api/v1/auth/invitations/inspect` `{token}` | 초대의 유효성·만료 시각 확인, 대상 이름은 공개하지 않음 |
| `POST /api/v1/auth/register` `{invitationToken,loginId,password,displayName}` | `201` 회원 생성·초대 소비, 자동 로그인 없이 로그인 화면으로 이동 |
| `POST /api/v1/auth/login` `{loginId,password}` | `200` 현재 회원, 세션 ID·CSRF 갱신 |
| `GET /api/v1/auth/me` | 활성 본인 정보·현재 전역 역할 |
| `POST /api/v1/auth/logout` | `204`, 서버 세션·쿠키 종료 |
| `POST /api/v1/invitations` `{recipientName,expiresAt?,replacesInvitationId?}` | 전체 관리자만 발급, 원문 token을 이 응답에서만 제공 |
| `GET /api/v1/invitations` | 전체 관리자만 목록 조회, 원문 token 제외 |
| `DELETE /api/v1/invitations/{id}` | 전체 관리자만 미사용 초대 해지 |

재발급은 `replacesInvitationId`로 기존 초대를 명시한다. 같은 이름의 다른 사람을 잘못 해지하지 않도록 이름 일치만으로 기존 초대를 폐기하지 않는다. 해지·재발급·가입은 같은 초대 행을 잠그며 가입과 초대 소비, 재발급과 기존 해지는 각각 한 트랜잭션이다. 실패한 가입은 초대를 소비하지 않는다.

아래 값은 초기 구현의 **기술 설정**이며 새로운 동아리 운영 정책을 확정한 것이 아니다.

- 로그인 ID: 3~32자, 첫 글자는 영문·숫자, 이후 영문·숫자·`.`·`_`·`-`. 앞뒤 공백 제거·소문자로 정규화하고 DB unique로 중복을 막는다.
- 표시 이름·초대 관리용 대상 이름: 공백 제거 후 1~40자.
- 비밀번호: 최소 12자, BCrypt의 입력 한도에 맞춰 UTF-8 최대 72바이트. 비밀번호의 앞뒤 공백을 임의로 제거하지 않는다. BCrypt 계산 비용 12를 기본으로 사용한다.
- 초대: 추측하기 어려운 임의 token, DB에는 SHA-256 digest만 저장. 기본 유효기간 7일이며 관리자가 만료 시각을 지정할 수 있다.
- 회원 상태 `INACTIVE`는 현재 권한 차단을 검증하는 기술 상태다. 이번 단계에서 탈퇴·회원 차단·역할 변경 관리 API를 제공하지 않는다. 탈퇴 API는 미래 예약 정리까지 구현하는 다음 범위에 포함한다.

미인증은 `401 AUTHENTICATION_REQUIRED`, 잘못된 로그인 ID·비밀번호·비활성 계정 로그인은 일관된 `401 INVALID_CREDENTIALS`다. 사용·해지·만료된 초대는 `410 INVITATION_UNAVAILABLE`, 중복 로그인 ID는 `409 LOGIN_ID_TAKEN`이다. 비밀번호·해시·세션 비밀값은 API 응답·오류·로그에 넣지 않는다. 초대 원문은 관리자 발급 응답에서만 한 번 제공하며 목록·오류·로그에는 넣지 않는다.

인증 제한은 공유 Next 프록시의 IP나 임의 `X-Forwarded-For`를 신뢰하지 않는다. 로그인 ID digest별 실패 10회/10분, 초대 token digest별 확인·가입 60회/10분, 단일 앱 전체 인증 요청 600회/분과 BCrypt 동시 계산 4개를 기술 한도로 둔다. 성공한 로그인은 해당 실패 카운터를 초기화한다. 카운터는 만료·최대 10,000개의 메모리 항목으로 제한하며 앱 재시작 때 초기화된다. 다중 앱 인스턴스의 합산 제한은 보장하지 않는다. 첫 운영 단일 앱 범위에서 사용하고 운영 환경의 실제 요청 패턴을 확인해 조정한다. 예약 하루 한도와는 별개의 보안 설정이다.

## 로그인 유지

2026-10-10 사용자 확정 정책은 **마지막 접속 후 90일, 접속 시 연장**이다. 별도 체크박스 없이 로그인에 기본 적용한다. 90일 동안 접속하지 않거나 브라우저에서 쿠키를 삭제한 경우 다시 로그인한다. 로그아웃은 해당 브라우저 세션을 종료하며 다른 기기의 세션은 유지한다. 회원 비활성화·탈퇴는 다음 요청에서 현재 상태를 검사해 접근을 차단한다.

- Spring Session JDBC의 `spring.session.timeout: 90d`와 `SESSION` 쿠키의 `Max-Age=7776000`을 같은 설정값으로 맞춘다. 쿠키는 `HttpOnly`, `SameSite=Lax`, `Path=/`, 운영 HTTPS의 `Secure`를 유지하며 비밀번호·로그인 토큰을 브라우저 저장소에 넣지 않는다.
- 유효한 활성 회원의 인증 요청 때 DB의 마지막 접근 시각과 90일 유휴 만료를 갱신하고 쿠키의 `Max-Age`·`Expires`도 다시 발급한다. 기본 Spring Session은 같은 ID의 쿠키를 매 요청 다시 발급하지 않으므로 `CurrentMemberFilter` 뒤의 `PersistentLoginCookieFilter`에서 응답 본문 쓰기 전에 갱신한다. 화면을 열어 둔 상태만으로 자동 연장하지 않으며 서버 요청을 기준으로 한다.
- 로그인은 ID·CSRF 갱신 후 세션의 유휴 제한도 재설정한다. 만료되지 않은 기존 30분 인증 세션은 다음 인증 요청에서 90일로 전환한다. 이미 만료된 세션은 되살리지 않으며 한 번 다시 로그인해야 한다.
- 로그아웃 경로에는 연장 쿠키를 쓰지 않는다. 기존 서버 세션을 삭제하고 삭제 쿠키를 내려보내며 이전 쿠키를 다시 보내도 인증할 수 없다. 비활성·탈퇴·존재하지 않는 회원은 상태 검사에서 요청을 종료해 장기 쿠키를 갱신하지 않는다.
- 세션·보안 컨텍스트는 기존 PostgreSQL 테이블을 사용한다. 같은 DB로 서버를 재시작해도 유효한 세션을 복원한다. DB를 초기화하거나 세션 데이터를 제거하면 로그인이 종료된다. 별도 remember-me 토큰, JWT 갱신, DB migration은 추가하지 않는다.

공용 기기에서 사용 후 로그아웃하도록 로그인 화면에 안내한다. 브라우저의 쿠키 삭제·저장 제한이나 운영 HTTPS·실기기 동작은 코드만으로 보장하지 않는다. 기존 개발 DB와 Docker 볼륨은 변경하지 않고 통합 테스트는 별도 PostgreSQL 컨테이너를 사용한다.

### 로그인 유지 검증 결과

2026-10-10 01:30~01:40 KST, `codex/persistent-login`에서 검증했다.

| 검증 | 실제 결과 |
|---|---|
| Backend 전체 `test bootJar` | 기존 41건 + 새 10건 = 51건 통과, 실패·오류·건너뜀 0, JAR 생성 성공 |
| 추가 회귀 후 `test --tests kr.jeongeum.scheduler.PersistentLoginIntegrationTest` | 장기 로그인 11건 모두 통과. 기존 41건과 합해 서로 다른 52건을 검증 |
| PostgreSQL·HTTP | 90일 쿠키·DB 만료, 45일 유휴 후 새 HTTP 클라이언트의 쿠키 복원과 만료 연장, 90일 만료 직후·91일 거절, 유효 기존 30분 세션 전환·만료된 기존 세션 재활성화 방지, 익명 요청 무갱신, 로그아웃 후 재사용 거절·다른 기기 세션 유지, 비활성·탈퇴 상태 반영 통과 |
| 독립 JVM 재시작 | 같은 임시 DB로 웹 JAR을 종료·재실행한 뒤 이전 쿠키로 동일 회원·세션 복원 및 로그아웃 후 재사용 거절 통과. 테스트가 시작한 자식 프로세스만 정리 |
| Frontend | 기존 29건·타입 검사·lint·production build 통과. 로그인 유지 안내 추가, 사용자 하단 문구 보존 |
| Git·보존 | 변경 파일·신규 파일의 whitespace 검사, 고확률 secret 패턴·실제 개발 DB 비밀번호 포함 여부·민감 파일 추적 검사에서 발견 0. 새 migration 없이 기존 개발 DB의 V1 이력과 `jeongeum-dev_postgres_data` 볼륨 유지 |

새 HTTP 클라이언트의 쿠키 복원은 실제 브라우저·PWA 종료/재실행 검증과 구분한다. Next.js의 설치된 프록시 코드는 복수 `Set-Cookie`와 `Max-Age`·`Expires`를 전달하는 것으로 검토했으며 이번 단계에서 프록시를 통한 실제 HTTP 재검증·운영 HTTPS·실기기 검증은 수행하지 않았다. 위 로컬 검증 보고 시점에는 커밋·Push·PR 생성과 원격 CI를 실행하지 않았다. 이후 사용자가 커밋·Push·main 대상 PR 생성을 승인했으며, 게시한 커밋의 원격 CI 결과는 해당 PR의 Checks에서 확인한다. main 병합은 별도 승인 단계다.

## 로컬 실행과 최초 관리자

DB·앱 실행은 [INITIAL_SETUP](INITIAL_SETUP.md)을 따른다. 웹 시작 때 관리자 계정을 자동 생성하지 않는다. 첫 전체 관리자는 비웹 초기화 프로필로 **회원 테이블이 비어 있을 때만** 한 번 생성한다. 기존 계정의 역할이나 비밀번호를 덮어쓰지 않는다.

먼저 백엔드 JAR을 만든다.

```powershell
Push-Location ./backend
try { ./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon bootJar }
finally { Pop-Location }
```

프로젝트 루트에서 다음 명령을 실행한다. 비밀번호는 터미널의 숨겨진 입력으로 두 번 입력한다. 채팅·명령 인수·Git 파일에 비밀번호를 넣지 않는다.

```powershell
./scripts/Initialize-Admin.ps1
```

스크립트는 기존 로컬 `.env`·DB 비밀번호 파일을 사용하고 관리자·DB 자격증명을 자식 프로세스 환경에만 전달한다. 실행 후 이전 환경을 복원한다. 직접 JAR을 실행하는 환경에서는 `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD`와 `APP_BOOTSTRAP_LOGIN_ID`, `APP_BOOTSTRAP_DISPLAY_NAME`, `APP_BOOTSTRAP_PASSWORD`를 별도의 비밀 환경으로 설정한다.

```powershell
java -jar ./backend/build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=bootstrap-admin --spring.main.web-application-type=none
```

웹 화면은 `/login`, `/join`, 로그인 후 `/`, 전체 관리자 `/admin/invitations`다. 초대 원문은 가입 화면에 직접 입력하며 URL·브라우저 저장소에 넣지 않는다. 가입 후 로그인은 별도로 한다. 로그아웃과 세션 상실 때 화면의 개인 Query 캐시를 제거한다. 오프라인 변경은 실패로 표시하고 재연결 때 자동 실행하지 않는다.

## 검증 결과와 제한

2026-10-09 19:41~19:44 KST, 중단 이후 남아 있던 `codex/authentication-foundation`의 미커밋 코드를 보존하고 다시 검증했다. 기준 HEAD·main·origin/main은 `73386a4`다. 기존 코드를 검토한 결과 아래 동시성·캐시 수정과 회귀 테스트가 이미 포함되어 있었고, 누락된 검증 문서와 프론트 README를 마무리했다.

- 해지된 원본 초대 재발급을 거절해 여러 유효 후속 초대가 생기지 않는다. 서로 다른 관리자 두 명으로 동시 재발급을 검사한다.
- 세션 캐시 정리 때 현재 회원 Query의 관찰자를 보존한다.
- 취소된 이전 401 응답이 새 로그인 상태를 제거하지 않도록 AbortSignal을 검사한다.

| 검증 | 실제 결과 |
|---|---|
| Backend `test bootJar --rerun-tasks` | 19건 통과, 실패·오류·건너뜀 0, JAR 생성 성공 |
| 실제 PostgreSQL 테스트 | 인증 통합 11건·기존 보안/세션 5건, 별도 임시 컨테이너에서 V1→V2 적용 |
| 인증 제한 단위 테스트 | 3건 통과: 만료·성공 시 초기화, 초대/전체 한도, BCrypt 동시 계산 제한 |
| Frontend Vitest | 4개 파일·18건 통과: 입력·권한·민감 캐시·관찰자·취소 응답·화면 접근 |
| Frontend 타입·lint·production build | 모두 통과, lint 오류·경고 0 |
| 런타임 `npm audit --omit=dev --audit-level=high` | 취약점 0건 |
| 별도 임시 DB의 실행 JAR·Next 프록시 | 최초 관리자 생성·두 번째 생성 거절, 로그인 세션 ID 갱신·지속, 초대 발급/확인/가입/소비, 일반 회원 관리자 접근 거절, 로그아웃·쿠키 해제 통과 |
| 민감정보 검사 | 추적/신규 비무시 파일 후보에 실제 로컬 DB 비밀번호 일치·고신뢰 비밀키 패턴 0건, 금지 secret·산출물 후보 0건 |
| 관리자 PowerShell 초기화 스크립트 | 구문 검사 통과, 숨겨진 입력·환경 복원·비밀값 정리 검토; 실제 개발 관리자 생성은 실행하지 않음 |

초대 소비 경쟁은 정확히 회원 한 명만 생성하고, 서로 다른 초대의 동일 로그인 ID 경쟁은 실패한 초대를 다시 쓸 수 있다. 가입↔해지 경합에서는 소비·해지가 동시에 성립하지 않는다. 현재 관리자 권한은 회원 행 잠금 대기 후에도 다시 검사한다. 세션 ID·CSRF 갱신, 비활성·권한 회수 반영, 비웹 초기화 프로세스 두 개의 경합도 실제 PostgreSQL 테스트에 포함했다.

최초 프론트 실행은 Windows 샌드박스 임시 파일 접근 오류로 일부 suite를 로드하지 못했다. 정상 Windows 실행 권한으로 재실행한 최종 18건이 모두 통과했으며 코드 실패로 처리하거나 최초 실행을 통과했다고 기록하지 않는다. npm 감사도 샌드박스 DNS 실패 뒤 재실행한 결과다.

기존 `jeongeum-dev-postgres-1`와 `jeongeum-dev_postgres_data` 볼륨은 유지했다. 개발 DB의 migration은 V1 한 건과 세션 테이블 그대로이며, 이번 검증에서 V2 적용·개발 관리자 생성·데이터 삭제를 하지 않았다. API 연결 검증은 별도 임시 DB를 만들고 끝난 뒤 그 검증용 컨테이너만 종료했다. V1 파일은 변경하지 않았다. 테스트용 합성 계정의 비밀번호와 초대 원문은 무작위로 생성하고 Git·문서·출력에 저장하지 않았다.

기존 CI는 백엔드 테스트·JAR, 프론트 타입·lint·테스트·빌드와 런타임 감사를 실행한다. 인증 구현 커밋 `b2f15de`의 [PR CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/37920225547)와 [Push CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/37920201260)는 모두 통과했다. 최종 화면 수정 이후의 결과는 [PR #3의 Checks](https://github.com/developer-sw/jeongeum-band-scheduler/pull/3/checks)에서 해당 커밋 기준으로 확인한다. 사용자가 PR #3의 실제 화면 최종 점검과 main 반영을 승인했다. 운영 의존성과 별개인 개발용 lint 의존성의 기존 감사 항목은 [Frontend README](../frontend/README.md)를 따른다.

### 실제 브라우저 최종 점검

2026-10-09, 인앱 브라우저에서 실행 중인 Next 개발 서버와 별도 임시 PostgreSQL·백엔드 JAR로 아래 흐름을 확인했다. 개발 DB 대신 검증용 합성 계정만 사용했다.

| 화면 흐름 | 실제 결과 |
|---|---|
| 잘못된 비밀번호·관리자 로그인 | 오류 안내 후 비밀번호 입력 제거, 정상 로그인 후 전체 관리자 홈 표시 |
| 관리자 초대 발급·재발급·해지 | 새 코드 발급, 이전 코드 무효화, 해지 상태 표시; 코드 패널 닫기 후 원문 제거 |
| 해지·소비한 초대 재사용 | 가입을 거절하고 초대 이용 불가 안내 표시 |
| 유효한 초대로 가입 | 입력 길이 검증, 일반 회원 생성, 자동 로그인 없이 로그인 화면 이동 |
| 가입 완료 안내 | `/login?joined=1` 이동·새로고침에서 표시, 일반 `/login` 메뉴 이동 시 제거 |
| 일반 회원 로그인 | 대문자 ID 정규화, 회원 홈 표시, 관리자 메뉴 없음 |
| 일반 회원의 관리자 URL 직접 접근 | 관리자 전용 안내, 발급 버튼 없음 |
| 로그아웃 후 뒤로 가기·새로고침 | 로그인 필요 안내, 이전 회원 정보·관리자 작업이 다시 나타나지 않음 |
| 초대 입력 후 다른 화면 이동·뒤로 가기 | 이전 초대 입력이 제거됨 |

PC 화면과 390×844 모바일 viewport에서 폼·관리자 목록을 확인했다. 가입 후 완료 안내가 숨겨지는 문제를 실제 화면에서 발견해 수정했다. 비민감 `joined=1` query를 Next의 `useSearchParams`로 읽고 `Suspense` 경계를 두어 정적 빌드와 링크 이동을 지원한다. 새 검증 회원 가입·완료 화면 새로고침·일반 로그인 메뉴 이동까지 회귀 검증했고, 수정 후 기존 프론트 18건·타입·lint·production build도 통과했다. 검증 화면 캡처는 Git에서 제외한 로컬 `.local`에 보관한다. 이 검증은 개발 서버의 브라우저 흐름이며 실기기·운영 HTTPS·Push 수신 검증을 대신하지 않는다.

팀장·소속 권한, 탈퇴 예약 정리, Push 기기 정리, 운영 HTTPS·프록시·실기기 확인은 후속 구현·운영 검증이다. 비밀번호 재설정과 SMTP는 이번 범위에 추가하지 않는다.
