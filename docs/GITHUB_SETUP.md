# GitHub 초기 설정

기준일: 2026-10-09 / 현재 승인 범위: GitHub 재인증, 독립 저장소 초기 설정과 연결

이 단계는 애플리케이션 구현·Docker 정상화·CI 실행 설정·유료 서비스 생성과 구분한다. 기존 참고 저장소는 수정하지 않는다.

## 저장소 기준

- 이름: `jeongeum-band-scheduler`
- 공개 범위: **비공개** — 사용자가 이번 단계에서 선택했다.
- 기본 브랜치: `main`
- 기존 전역 Git 사용자 이름·이메일·credential 설정을 임의로 덮어쓰지 않는다.
- GitHub 계정: `developer-sw` — 브라우저 기기 인증 후 API와 CLI 상태로 확인했다.
- 원격 저장소: [developer-sw/jeongeum-band-scheduler](https://github.com/developer-sw/jeongeum-band-scheduler)
- 로컬 origin: `https://github.com/developer-sw/jeongeum-band-scheduler.git`
- 같은 이름의 원격 저장소가 이미 있으면 먼저 내용·소유자·공개 범위를 확인한다. 다른 프로젝트의 내용을 덮어쓰거나 강제로 push하지 않는다.
- 오픈소스 라이선스는 아직 결정하지 않았으므로 임의로 추가하지 않는다.

## 로컬 기본 파일

- `.gitignore`: Java/Gradle·Node/Next.js 산출물, 실제 환경변수, 인증 키, 로컬 DB 데이터와 백업, 로그와 로컬 IDE 설정 제외.
- `.gitattributes`: 텍스트 LF, Windows `.bat`·`.cmd` CRLF, 이미지·폰트·JAR 등 binary 처리.
- `.github/PULL_REQUEST_TEMPLATE.md`: 문제·동작·검증·정책 영향의 짧은 기록 양식.
- Gradle Wrapper/JAR, `package-lock.json`, Flyway migration은 추적 가능한 상태로 유지한다. `*.jar`·`*.sql` 전체 제외 규칙을 추가하지 않는다.

## 브랜치와 PR

1. 초기 설계 문서와 저장소 규칙을 첫 commit으로 기록한다.
2. 이후 기능·수정은 `feature/...`, `fix/...`, 문서 작업은 `docs/...` 같은 브랜치에서 진행한다.
3. PR에서 변경 이유와 실제 검증 결과를 기록한다.
4. 구현 후 CI 결과와 검토를 확인하고 main에 병합한다.
5. main에 직접 기능을 개발하거나 force push하지 않는 운영 규칙을 유지한다.

GitHub Free 비공개 저장소에서는 PR과 Actions를 사용할 수 있지만 보호 브랜치·ruleset 강제 적용은 플랜에 따라 제한된다. 사용 가능한 보호 기능은 실제 계정 플랜을 확인한 뒤 적용한다. 이 단계에서 유료 플랜으로 자동 업그레이드하지 않는다. [GitHub 보호 브랜치 안내](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)

## CI 준비 범위

backend/frontend 초기 구성과 함께 Java 21·Gradle·PostgreSQL Testcontainers, 프론트 타입 검사·lint·테스트·build를 Linux runner에서 수행하는 기본 CI를 작성했다. 실제 실행 결과는 [INITIAL_SETUP.md](INITIAL_SETUP.md)에 기록한다. 운영 secret은 테스트에 넣지 않는다. 필요 권한과 포함 사용량은 [배포 계획](DEPLOYMENT.md)의 기준을 따른다.

## 인증과 진행 기록

- 브라우저 기기 인증을 완료했다. `gh auth status`와 GitHub API를 외부 연결 권한으로 확인하여 `developer-sw` 인증 성공을 검증했다.
- 샌드박스에서는 GitHub API 연결이 실패하며 인증 상태도 실패로 표시될 수 있었다. 네트워크 제한과 실제 인증 실패를 구분해 확인했다.
- Windows 자격증명 저장소를 사용한다. token·기기 인증 원문은 문서나 Git에 저장하지 않는다.
- 로컬 `main` 초기화와 새 비공개 원격 저장소 생성, origin 연결을 완료했다. 기존 같은 이름 저장소가 없음을 확인한 뒤 생성했다.
- 전역 Git 사용자 정보와 인증 helper는 유지했다. 이 저장소의 GitHub HTTPS helper만 `gh auth git-credential`로 연결했다.
- Wiki는 비활성화했고 프로젝트 문서는 저장소의 docs에서 관리한다. Issues와 PR은 사용할 수 있다.
- `.gitignore`의 환경변수·산출물·DB 백업 제외, lock 파일·Wrapper·migration 추적 가능 상태와 줄바꿈 속성을 확인했다.
- 첫 commit [10868bb](https://github.com/developer-sw/jeongeum-band-scheduler/commit/10868bba8a68aa0736922294033a29aea749f1da)에 설계 문서와 Git 설정 파일 15개를 기록하고 `main`으로 push했다.
- `main`은 `origin/main`을 추적한다. 원격 기본 브랜치 `main`, 비공개 상태와 첫 commit의 로컬·원격 SHA 일치를 확인했다.
- staged diff의 공백 오류 검사와 인증 키 패턴 검사를 통과했다. 애플리케이션 코드가 없어 앱 테스트와 CI 실행은 아직 수행하지 않았다.
- GitHub 인증을 받기 위해 비밀번호나 장기 token을 채팅에 제출하지 않는다. 브라우저에서 직접 인증한다.

## 다음 단계

Docker 정상화는 [DEV_ENVIRONMENT.md](DEV_ENVIRONMENT.md), backend/frontend 초기 실행·연결·CI 검증은 [INITIAL_SETUP.md](INITIAL_SETUP.md)에 기록한다. PR 생성·정식 전환·main 병합은 요청한 사용자 승인을 받은 뒤 진행한다.
