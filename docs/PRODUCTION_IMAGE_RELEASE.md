# 운영 이미지 발행 준비

기준일: 2026-10-17, Asia/Seoul. 운영 컨테이너 [PR #23](https://github.com/developer-sw/jeongeum-band-scheduler/pull/23)의 main 병합 `911c181` 이후 `codex/production-image-release`에서 준비한다. 이번 범위는 발행 도구·수동 workflow·검증·문서이며 실제 GHCR 발행·서버 배포를 수행한 기록이 아니다.

후속으로 사용자가 커밋·Push·main 대상 초안 PR·CI 확인을 승인했다. 검증한 준비 소스와 문서를 게시하며 현재 커밋의 원격 CI는 해당 PR에서 확인한다. 로컬 검사 결과와 원격 CI·실제 GHCR 발행 결과를 구분하고 수동 이미지 발행 workflow는 실행하지 않는다.

## 발행 대상과 권한

기술 구성은 기존 저장소에 연결하는 GHCR과 Linux `amd64` 단일 플랫폼이다. Backend·Frontend·Caddy만 자체 이미지로 발행하고 PostgreSQL은 기존 Compose의 공식 이미지 digest 고정을 유지한다.

| 구성 요소 | 이미지 저장소 |
|---|---|
| Backend | `ghcr.io/developer-sw/jeongeum-band-scheduler/backend` |
| Frontend | `ghcr.io/developer-sw/jeongeum-band-scheduler/frontend` |
| Caddy | `ghcr.io/developer-sw/jeongeum-band-scheduler/caddy` |

새 GHCR package는 기본 비공개다. 이미 존재하는 package는 비공개이며 원본 저장소와 연결됐는지 확인하고, 공개·internal·연결 불명 상태는 발행을 중단한다. visibility를 자동 변경하지 않는다. [GHCR 공식 문서](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry), [저장소 연결](https://docs.github.com/en/packages/learn-github-packages/connecting-a-repository-to-a-package)

GitHub Actions의 `GITHUB_TOKEN`을 실행 시에만 사용한다. 준비 job은 `contents: read`·`actions: read`, 발행 job만 추가로 `packages: write`를 가진다. 별도 PAT 생성·저장소 secret 등록·GitHub Release 생성·Git tag Push는 이 도구가 하지 않는다. 서버에서 비공개 이미지를 pull할 때 필요한 별도 `read:packages` 인증과 계정 접근권은 실제 서버 준비 단계에서 구성한다. [공식 workflow 권한 설명](https://docs.github.com/en/packages/managing-github-packages-using-github-actions-workflows/publishing-and-installing-a-package-with-github-actions)

## 준비와 발행의 분리

진입점은 [release-images.yaml](../.github/workflows/release-images.yaml)이며 `workflow_dispatch`만 사용한다. 원본 저장소의 `main`을 선택하고 `version`에 `v1.0.0` 또는 `v1.0.0-rc.1` 형식을 입력한다. `publish`의 기본값은 `false`다. 자동 Push·PR·tag 이벤트는 발행을 시작하지 않는다. workflow가 main에 반영되기 전에는 GitHub의 수동 실행 대상이 아니다.

1. **준비:** 실행 SHA와 checkout HEAD·원격 main이 일치하며 같은 SHA의 main Push CI가 성공했는지 확인한다. 기존 Backend·Frontend·운영 컨테이너 검사와 새 발행 계약 검사를 확인한다. 건너뛰거나 실패한 CI를 성공으로 취급하지 않는다.
2. 세 Dockerfile에서 이미지를 한 번 빌드한다. source·revision·version OCI label을 붙이며 실제 DB/Push secret을 build arg나 이미지에 넣지 않는다.
3. 같은 이미지를 `Test-ProductionContainers.ps1 -SkipBuild`로 검증한다. 합성 환경 57개 검사 성공과 QA 정리·기존 개발 DB 미접근·외부 Push 미발송이 필요하다. 검사 전후 이미지 ID를 비교한다.
4. 검증한 세 이미지 자체를 `images.tar`에 저장한다. archive checksum·크기·이미지 ID·출처·CI·비민감 QA 증빙을 `candidate.json`·`qa-summary.json`에 기록한다. 준비 단계는 registry에 쓰지 않는다.
5. **발행:** `publish=true`인 실행에서만 같은 실행의 candidate를 내려받아 출처·archive·CI·현재 main을 다시 검사한다. 세 이미지 전체의 기존 tag 충돌을 먼저 검사하고 검증한 archive를 load한다. 재빌드하지 않는다.
6. 전체 SHA tag를 발행한 뒤 registry의 manifest digest와 config digest를 확인한다. `repo@sha256:...`로 다시 pull한 이미지가 시험한 이미지 ID와 일치해야 version tag를 붙인다. 단일 manifest를 유지하는 `imagetools create --prefer-index=false`를 사용한다. [Docker 공식 설명](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/)
7. 세 이미지의 검증과 version tag 확인이 모두 성공한 경우에만 `release.json`·`release.env`를 작성한다. 운영 Compose에는 이 최종 결과의 digest 참조를 사용한다.

Node 실행 형태는 다음과 같다. 두 명령은 CI의 원본 저장소·수동 main 실행·토큰을 검증하므로 일반 로컬 터미널에서 실제 발행을 우회하는 경로로 사용하지 않는다.

```text
node scripts/production-image-release.mjs prepare --version v1.0.0-rc.1 --directory release-artifacts
node scripts/production-image-release.mjs publish --version v1.0.0-rc.1 --directory release-artifacts
```

## Tag·digest와 재시도

두 tag는 `sha-<전체 40자 Git SHA>`와 입력한 version이다. `latest`·`main` 같은 가변 별칭은 만들지 않는다. 이미지의 로컬 image ID는 config digest이며 registry manifest digest와 다르다. 최종 운영 참조는 반드시 **저장소@manifest digest**다.

기존 tag가 같은 이미지·digest를 가리키면 재사용하고 다른 내용이면 중단한다. 이는 발행 도구의 덮어쓰기 방지 규칙이며 GHCR 전체의 tag 불변성을 보장하는 설정은 아니다. 저장소 공통 concurrency로 이 workflow의 중첩 실행을 직렬화해도 다른 관리자나 도구의 변경까지 차단하지는 못한다.

이미지에 version label도 포함하므로 같은 Git SHA의 이미지를 다른 version으로 재빌드하면 내용이 달라진다. 이미 발행한 SHA는 원래 version으로만 재개하고 새 version은 새 소스 커밋에서 준비한다. 수동 실행 전체를 다시 시작하면 다시 빌드·QA하므로, 부분 발행 복구에는 같은 실행의 실패 job 재실행을 사용한다. candidate artifact 이름은 run ID와 SHA로 고정하며 전체 재실행이 기존 artifact를 덮어쓰지 않는다.

세 이미지의 registry 쓰기는 하나의 트랜잭션이 아니다. 중간 실패 시 이미 생성한 이미지를 삭제하거나 tag를 덮어쓰지 않는다. 같은 실행의 검증 candidate를 유지해 실패 job을 재실행하고, 최종 release 산출물이 없으면 배포 입력으로 사용하지 않는다. main이 이동하거나 기존 version/SHA의 내용이 다르면 원인을 확인하고 새 후보를 준비한다. 재빌드는 같은 소스라도 APT 등 외부 입력에 따라 이미지 ID가 달라질 수 있으므로 기존 SHA tag를 덮어써 해결하지 않는다.

## 산출물과 비밀값 경계

`release-artifacts/`는 Git 제외 경로다. Actions candidate artifact에는 `images.tar`·`candidate.json`·`qa-summary.json` 세 파일만, 최종 artifact에는 `release.json`·`release.env` 두 파일만 허용하고 보관기간은 7일이다. 준비 후보의 대용량 image tar를 영구 백업으로 사용하지 않는다.

기존 `.local/production-qa-*`에는 합성 비밀번호·세션·dump·CA 등이 있으므로 폴더째 업로드하지 않는다. QA 실행기의 선택적 `ResultPath`는 `.local/production-image-release-qa-<UUID>.json` 새 파일만 허용하며 안전한 결과 필드만 내보낸다. 경로 이탈·symlink·덮어쓰기를 거부한다. Docker 인증은 매번 생성한 임시 `DOCKER_CONFIG`에서 stdin으로 전달하고 해당 디렉터리만 정리한다. 사용자 Docker 로그인 설정을 재사용하거나 지우지 않는다.

Registry manifest 조회 직전에 매번 고정 GHCR endpoint에서 bearer token을 새로 받아 긴 Push/Pull 이전의 토큰을 재사용하지 않는다. 인증 오류 401/403은 이미지가 없다는 뜻으로 취급하지 않고 발행을 중단한다. 새 package의 첫 Push 전/후 권한과 GitHub 메타데이터 반환은 실제 workflow에서 확인할 항목이다. API의 404만으로 접근 불가와 미생성을 모두 구분했다고 주장하지 않으며, registry 조회·발행 후 비공개/연결 상태 확인까지 성공해야 완료된다. [Docker token 인증 규격](https://docs.docker.com/reference/api/registry/auth/)

최종 release artifact 이름에는 실행 attempt도 포함하므로 결과 업로드 뒤 재시도해도 앞선 결과를 덮어쓰지 않는다. 입력 candidate는 원본을 보존하기 위해 run ID·SHA 이름을 유지한다.

`release.env`는 다음 세 **이미지 참조**만 포함한다. 실제 DB·VAPID·관리자 자격증명은 별도 운영 secret 파일에만 둔다. 이 파일 자체는 완전한 운영 `.env`가 아니며 hostname·네트워크·worker OFF 등 [운영 설정 예시](../deploy/.env.production.example)의 다른 설정이 필요하다.

```text
BACKEND_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/backend@sha256:<검증한 manifest digest>
FRONTEND_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/frontend@sha256:<검증한 manifest digest>
CADDY_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/caddy@sha256:<검증한 manifest digest>
```

## 검증과 다음 단계

자동 검사는 Docker·registry·GitHub API 경계를 대체하여 버전/저장소/branch/CI, 경로·artifact 무결성, 권한·manifest/config digest, 충돌·부분 실패·재시도와 비밀값 노출 방지를 검증한다. 실행 결과는 [TEST_PLAN](TEST_PLAN.md)의 이번 단계 기록을 따른다. Mock 기반 계약 검사는 실제 GHCR Push/Pull 성공이나 새 합성 컨테이너 실행 성공의 증거가 아니다.

로컬 검증은 Node 28건·PowerShell export 15항목 모두 통과했고 실패/건너뜀은 0이다. CI와 수동 workflow는 actionlint 정적 검사 및 구문 검사를 통과했다. 이전 합성 QA 57개 결과를 새 validator가 수용하는 것도 확인했다. 토큰 갱신·최종 artifact 재시도 보완 후 독립 검토에서 추가 확정 차단 결함을 발견하지 못했다.

직전 운영 컨테이너 병합 main CI는 [39006934855](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39006934855)이며 Backend 368건·Frontend 334건·합성 컨테이너 57개와 빌드가 성공했다. 이번 발행 준비와 구분한다.

후속 순서는 변경사항 검토·승인된 GitHub 반영과 CI, main에 반영된 수동 workflow의 `publish=false` 준비 실행, 별도 발행 승인 후 비공개 GHCR 실제 Push/Pull·digest 확인이다. 이후 서버/도메인 준비·외부 암호화 백업/복원·새 운영 origin/기기 구독·worker 단계별 활성화와 실제 운영 검증을 진행한다. 실제 개발 DB 이전·서버 구매·배포는 이 준비 작업에 포함하지 않는다.
