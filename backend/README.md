# Backend

Java 21 · Spring Boot 4.1.1 · Gradle Wrapper 9.8.1. 버전은 공식 Spring Initializr와 [Spring Boot 지원 조건](https://docs.spring.io/spring-boot/4.1/system-requirements.html)에서 확인했다. 의존성은 Boot BOM과 `gradle.lockfile`로 고정한다.

프로젝트 루트의 `scripts/Start-Backend.ps1`로 개발 PostgreSQL에 연결한다. 스크립트는 로컬 비밀번호 파일을 프로세스 환경에만 전달한다. 직접 실행할 경우 `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD`를 모두 지정해야 한다. 비밀번호 기본값은 없다. 로컬 HTTP는 `SPRING_PROFILES_ACTIVE=local`로 실행하며 그 외 환경의 세션 쿠키는 Secure다.

```powershell
# backend 폴더에서 실행, Docker Desktop의 Linux 엔진 필요
./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon test bootJar
```

테스트는 개발 DB와 분리된 임시 PostgreSQL 컨테이너, 무작위 비밀번호와 포트를 사용한다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.

Health는 실제 DB 조회 결과를 최소 응답으로 반환한다. `GET /api/v1/auth/csrf`는 `data.headerName`, `data.token`을 반환하며 클라이언트가 동일 origin의 세션 쿠키와 함께 모든 변경 요청 헤더에 전달한다. 응답 토큰은 XOR 마스킹을 사용하고 서버 세션에 저장된다. 폼 로그인·HTTP Basic·생성된 기본 계정은 제공하지 않는다.

Flyway V1은 [Spring Session의 PostgreSQL 스키마](https://github.com/spring-projects/spring-session/blob/main/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql)를 기반으로 두 세션 테이블을 생성한다. V2는 `users`, `invitation_codes`와 최초 관리자 초기화 잠금 행을 추가한다. V3는 가입 전 명단·팀·소속·곡·역할 감사 이력·공통 변경 잠금과 초대의 명단 FK를 추가한다. V1/V2는 수정하지 않았다. Spring의 자동 스키마 생성은 끄고 Flyway가 관리한다. `ddl-auto=validate`, `open-in-view=false`를 유지한다.

`auth`, `member`, `invitation` 기능 패키지 안에 Controller·Service·Domain·Repository 책임을 나눈다. 초대 일회 소비, 로그인 ID 충돌, 관리자 권한과 회원 상태 검증은 PostgreSQL 트랜잭션과 행 잠금을 사용한다. SQL 잠금 경계를 명시하기 위해 이번 인증 Repository는 `JdbcTemplate`을 사용한다. JPA는 이후 업무 Domain에 사용할 준비를 유지한다.

초대 가입·JSON 로그인·본인 정보·로그아웃·전역 관리자 초대 발급/목록/해지를 제공한다. 가입은 `MEMBER`만 생성하며 자동 로그인하지 않는다. 본인 응답의 `teamMemberships`는 연결된 명단의 현재 정규조·추가조 소속과 팀 역할을 반환한다. 로그인은 세션 ID와 CSRF를 갱신하고 식별자만 세션에 저장한다. 회원 상태와 전역 역할은 요청마다 DB에서 다시 읽고 관리자 서비스 트랜잭션에서도 잠금 후 검증한다. 응답은 `no-store`이며 비밀번호·원문 초대 토큰을 로그나 오류에 남기지 않는다.

초대 토큰은 무작위 32바이트이며 DB에는 SHA-256만 저장한다. 원문은 발급 응답에서 한 번 제공한다. 기본 유효기간은 기술 초기값 7일이며 `app.auth.invitation-validity`로 조정하고 발급 요청에서 만료 시각을 지정할 수 있다. 재발급은 `replacesInvitationId`로 지정한 미사용 원본만 해지한다. 이름이 같은 다른 초대를 자동 해지하지 않는다.

기술 입력 기준은 로그인 ID 3~32자리 ASCII(첫 글자 영문/숫자, 이후 영문/숫자·점·밑줄·하이픈), 앞뒤 공백 제거와 소문자 정규화다. 이름은 공백 제거 후 1~40코드포인트다. 비밀번호는 공백을 보존하며 12코드포인트 이상·UTF-8 72바이트 이내, BCrypt 비용 12를 사용한다.

기본 시도 제한은 정규화한 로그인 ID별 실패 10회/10분, 초대 digest별 60회/10분, 전체 인증 요청 600회/분, 동시 BCrypt 계산 4개다. 제한 키는 digest만 사용하며 공유 Next.js proxy IP나 임의 전달 헤더를 사용하지 않는다. 한 로그인 ID의 실패가 다른 회원을 막지 않는다. 단일 프로세스 메모리·TTL·최대 10,000키를 사용하므로 재시작하면 초기화되고 여러 앱 인스턴스의 제한을 공유하지 않는다. 이 한계는 운영 확장 시 재검토한다.

최초 관리자 생성은 별도 명시적 `bootstrap-admin` 프로필과 process 환경 `APP_BOOTSTRAP_LOGIN_ID`, `APP_BOOTSTRAP_DISPLAY_NAME`, `APP_BOOTSTRAP_PASSWORD`를 사용한다. `APP_DB_*`도 필요하다. 비밀번호를 명령 인수에 넣지 않는다. 사용자 테이블이 비어 있을 때만 단일 잠금 행 아래 한 계정을 생성하고 종료한다. 기존 계정의 권한·비밀번호를 덮어쓰지 않는다. 일반 웹 실행에서는 이 초기화 서비스가 등록되지 않는다.

```powershell
java -jar ./build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=bootstrap-admin --spring.main.web-application-type=none
```

실제 운영 절차와 화면/API 계약은 [인증 구현 문서](../docs/AUTHENTICATION.md)를 따른다. 테스트는 실제 PostgreSQL·HTTP·JDBC 세션과 별도 non-web JVM 두 개의 동시 초기화를 포함한다.

명단·계정 연결, 관리자 역할, 복수 정규조·추가조, 팀원·팀장과 곡 관리 계약은 [회원·팀 관리 문서](../docs/MEMBER_TEAM_MANAGEMENT.md)를 따른다. 변경은 공통 guard를 먼저 잠근 뒤 현재 권한을 재검증한다. JSON 소수를 정수로 잘라 버리지 않고 거절한다. 2026-10-10 PostgreSQL 통합 검증을 포함한 41건과 `bootJar`가 통과했으며, 데이터가 있는 V2→V3 업그레이드와 권한·연결 경합도 확인했다. 기존 개발 DB에 V2/V3를 적용하지 않고 V1 상태와 지속 볼륨을 보존했다.
