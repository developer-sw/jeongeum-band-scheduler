# Backend

Java 21 · Spring Boot 4.1.1 · Gradle Wrapper 9.8.1. 버전은 공식 Spring Initializr와 [Spring Boot 지원 조건](https://docs.spring.io/spring-boot/4.1/system-requirements.html)에서 확인했다. 의존성은 Boot BOM과 `gradle.lockfile`로 고정한다.

프로젝트 루트의 `scripts/Start-Backend.ps1`로 개발 PostgreSQL에 연결한다. 스크립트는 로컬 비밀번호 파일을 프로세스 환경에만 전달한다. 직접 실행할 경우 `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD`를 모두 지정해야 한다. 비밀번호 기본값은 없다. 로컬 HTTP는 `SPRING_PROFILES_ACTIVE=local`로 실행하며 그 외 환경의 세션 쿠키는 Secure다.

```powershell
# backend 폴더에서 실행, Docker Desktop의 Linux 엔진 필요
./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon test bootJar
```

테스트는 개발 DB와 분리된 임시 PostgreSQL 컨테이너, 무작위 비밀번호와 포트를 사용한다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.

현재 공개 API는 `GET /api/v1/health`와 `GET /api/v1/auth/csrf`다. Health는 실제 DB 조회 결과를 최소 응답으로 반환한다. CSRF는 `data.headerName`, `data.token`을 반환하며 클라이언트가 동일 origin의 세션 쿠키와 함께 변경 요청 헤더에 전달한다. 응답 토큰은 XOR 마스킹을 사용하고 서버 세션에 저장된다. 로그인·예약 등 업무 API는 아직 제공하지 않으며 접근은 기본 거부한다. 기본 계정·폼 로그인·HTTP Basic도 제공하지 않는다.

Flyway V1은 [Spring Session의 PostgreSQL 스키마](https://github.com/spring-projects/spring-session/blob/main/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql)를 기반으로 두 세션 테이블만 생성한다. Spring의 자동 스키마 생성은 끄고 Flyway가 관리한다. 회원·팀·예약 테이블은 후속 기능 단계의 새 migration으로 추가한다. `ddl-auto=validate`, `open-in-view=false`를 유지한다.

패키지는 실제 기능 경계별로 나눈다. 현재 `auth/controller`, `health/controller`, `common/api`, `common/config`, `common/security`, `common/web`만 생성했다. 기능을 구현할 때 각 경계에 필요한 Service·Domain·Repository를 추가한다.
