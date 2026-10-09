# Java · Docker 환경 검증 도구

이 독립 Gradle 프로젝트는 JDK 21, Gradle Wrapper, JUnit 5, Testcontainers, PostgreSQL JDBC가 현재 컴퓨터에서 함께 동작하는지 확인한다. 예약 애플리케이션·Flyway migration·업무 규칙·동시성 테스트는 포함하지 않는다.

## 실행

Windows PowerShell에서 이 디렉터리로 이동한 뒤 Docker Desktop의 Linux 엔진이 실행 중인지 확인하고 실행한다.

```powershell
.\gradlew.bat --gradle-user-home .\.gradle\user-home --no-daemon test --rerun-tasks
```

첫 실행은 Gradle 배포본과 Maven Central 의존성 및 Docker 이미지를 내려받는다. Docker 접근과 인터넷 연결이 필요하다. JDK 21은 미리 설치되어 있어야 하며 이 도구가 JDK를 자동 설치하지 않는다.

Testcontainers가 Compose와 독립된 임시 PostgreSQL 컨테이너·임의 호스트 포트를 생성한다. 비밀번호는 실행 시 무작위로 생성하며 개발용 Compose의 자격증명이나 지속 볼륨을 사용하지 않는다. 테스트는 SQL 접속, PostgreSQL 17.11, commit·rollback 및 다른 연결에서의 결과를 확인한다. 컨테이너 재사용이나 Docker 환경 override를 설정하지 않으며 기본 Ryuk 정리를 유지한다.

Docker가 없으면 실패해야 한다. Docker 미실행을 테스트 skip으로 바꾸지 않는다. 결과는 `build/test-results/test/`와 `build/reports/tests/test/`에서 확인한다. 이 성공은 예약 정책·경합·서비스 기동·운영 준비 완료를 뜻하지 않는다.

## 고정 버전과 출처

확인일: 2026-10-09 (Asia/Seoul)

| 구성 | 버전 | 확인 출처 |
|---|---|---|
| Java | 21 | [프로젝트 아키텍처](../../docs/ARCHITECTURE.md) |
| Gradle | 9.8.1 | [공식 current API](https://services.gradle.org/versions/current), [Wrapper 안내](https://docs.gradle.org/current/userguide/gradle_wrapper.html) |
| JUnit Jupiter 5 | 5.14.4 | [Maven Central 공식 메타데이터](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml), 5.x 안정 버전 중 최신 |
| Testcontainers | 2.0.5 | [Maven Central 공식 메타데이터](https://repo.maven.apache.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml), [PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/) |
| PostgreSQL JDBC | 42.7.14 | [Maven Central 공식 메타데이터](https://repo.maven.apache.org/maven2/org/postgresql/postgresql/maven-metadata.xml) |
| PostgreSQL 이미지 | postgres:17.11-bookworm + 아래 digest | [공식 이미지](https://hub.docker.com/_/postgres), 로컬 Compose와 동일 이미지 |

Wrapper는 공식 Gradle 배포본의 `wrapper` task로 생성하며 배포 zip·Wrapper JAR의 SHA-256을 공식 공개 값과 비교한다. `distributionSha256Sum`이 이후 Wrapper 다운로드도 검증한다. `.gradle/`과 `build/`는 Git 제외 대상이며 Wrapper JAR·스크립트·설정은 추적한다. 부모 애플리케이션의 Spring Boot·Gradle 세부 버전은 별도 초기 구성 단계에서 확정한다.

Gradle 배포 zip SHA-256: `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`

Gradle Wrapper JAR SHA-256: `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`

PostgreSQL 이미지 digest: `sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826`
