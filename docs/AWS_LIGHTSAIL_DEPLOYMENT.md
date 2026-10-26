# AWS Lightsail 배포 준비

## 2026-10-26 후속 — 운영 DB 백업 구현·합성 검증

[운영 DB 백업·독립 복원·감시](OPERATIONAL_DATABASE_BACKUPS.md)와 [별도 배포 자산](../deploy/operational-backup/README.md)을 구현했다. 새 합성 PostgreSQL 원본·독립 복원 엔진의 실제 dump·age 검증과 로컬 버전 저장소/지표 simulator 검사를 완료했으며 운영 예시는 기본 비활성이다. 이번 단계에서는 AWS 자원·권한·비용·서버 설정·기존 합성 백업을 변경하지 않았다. 사용자 승인으로 변경 검토·커밋·Push·main 대상 초안 PR·원격 CI 확인을 진행하며 최신 원격 결과는 해당 초안 PR의 동일 HEAD 검사를 따른다. 이후 실제 운영 prefix·IAM/보존·경보를 검토하고 승인된 서버 설치·단발 백업/독립 복원·감시 수신·systemd 활성화를 확인해야 한다. 실제 데이터 이전·공개는 별도 승인 단계다.

## 현재 기준 — 2026-10-25

일반 **Paid Plan 전환 완료**, **Basic 지원과 기존 적격 크레딧 유지**가 현재 기준이다. 적격 요금은 크레딧으로 우선 상계하지만 초과·만료·제외 요금은 청구될 수 있으며 예산 알림은 과금 상한이 아니다. AWS 재문의의 사람 답변은 별도로 검토하고 계정별 금액·잔액·식별자는 비공개로 보관한다. 아래 Free Plan 기록과 초기 다음 순서는 각 날짜의 이력이다.

새 비활성 운영 설정 16파일의 로컬 암호화·독립 복원은 완료했으며 서버에 설치하지 않았다. 기존 서버 읽기 전용 점검과 [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md) 정리 후 소비자별 런타임 파일·Compose override를 구현하고 [합성 Linux 권한 검증](RUNTIME_SECRET_PERMISSIONS.md)을 통과했다. 기존 원본 16파일/암호문·준비 Caddy의 80/443·합성 백업 source guard는 유지한다. [새 25파일 설정 복구본](RUNTIME_SETTINGS_RECOVERY.md)의 독립 복원은 완료했다. 실제 서버 설치·운영 DB 생성/백업·앱 기동·실회원 데이터 이전·공개는 별도 승인 단계이며 이번 로컬 검증의 완료 범위가 아니다.

## 2026-10-24 합성 백업·재부팅 기록

AWS Basic 계정·결제의 Free Tier 문의를 승인 후 접수했고 답변 검토는 남아 있다. 정확한 기존 합성 PG만 시작하는 source unit·백업 준비 대기를 추가해 실제 VM 재부팅을 검증했다. 컨테이너/볼륨·원본 설정·DB/권한/Flyway 보존 16항목과 HTTPS 준비 화면의 자동 복구가 통과했다. QA Compose의 `restart: no`와 각 컨테이너의 기존 정책은 유지한다. 원래 hourly가 **16:00:18 KST** 실행돼 **16:00:24 KST** 새 S3 백업을 검증했고, 부팅 metric의 새 datapoint·CloudWatch OK·Free Plan 유지를 확인했다. 추가 3파일 설정의 별도 암호문은 기존 30파일 archive와 함께 PC 보관·실제 복원 검증했다. [재부팅 검증](OPERATIONS_REBOOT_VALIDATION.md)을 현재 기준으로 따른다.

아래는 재부팅 검증 전 정기 백업 구성 결과다.

사용자 승인으로 **Free Plan 유지**, 서울 비공개 S3 1개·최소 IAM·CloudWatch 지표 1개/경보 1개·SNS 이메일 구독 1개를 구성하고 합성 DB로 검증했다. 시간별 7일·주간별 28일·기본 Governance 7일·SSE-S3·공개 차단을 확인했다. exact-version 업로드/다운로드 SHA 대조, 중복 Put 412와 삭제·보존 변경·외부 prefix·다른 namespace 거부, 서버 서비스·타이머 활성/재시작 및 축약 trigger의 새 백업 생성 후 원래 hourly 설정 복구를 확인했다. stale ALARM/OK·양쪽 SNS action과 사용자 메일 수신도 확인했다. 추가 미수신 평가·최종 상태는 [OPERATIONS_BACKUP_PLAN](OPERATIONS_BACKUP_PLAN.md)을 따른다.

PC 독립 PG 복원 **10개·19.477초**와 실행기 오프라인 **27개**가 통과했다. QA 원본은 기존 **rc.2 / `8b24285`**, 신규 앱 배포에 재사용할 릴리스는 **rc.3 / `a00a791`**이며 서로 다른 검증 기준이다. 기존 수동 복원 20개·약 83초는 별도 기록이다. 이후 실제 VM 재부팅·정규 hourly 검증은 위 최신 결과와 구분한다.

당시에는 Paid 전환·실회원 데이터 이전·실제 앱 공개를 하지 않았다. 기존 서버·로컬 DB/볼륨·공개 준비 화면을 보존했다. PC 밖 개인키 보관·사본 확인은 사용자 확인으로 기록했다. 자동 PC 복사·새 운영 비밀 설정 복구·백업 요금의 실제 크레딧 상계·전체 RPO/RTO는 그 시점의 후속 항목이었다. 이후 비활성 설정 복원·Paid 전환은 위 현재 기준을 따른다. 계정별 금액·식별자·문의 번호·이메일·접속 주소·키·실패 원본은 비공개로 보관한다.

## 2026-10-18까지의 기본 구성·사전 검토 기록

이하 미등록·미생성·미실행 설명과 당시 다음 순서는 해당 날짜의 상태다. 최신 합성 백업·DNS/HTTPS 준비와 현재 승인 경계는 위 후속 결과를 우선한다.

계획 작성: 2026-10-17 / 읽기 점검 및 승인된 기본 구성: 2026-10-18, Asia/Seoul. 기준 앱 소스/이미지: `main` `8b24285bafda76ef21c3ae6148f34452a08d2483`, `v1.0.0-rc.2`. 사용자 직접 AWS 로그인 뒤 읽기 점검을 완료했고, 후속 승인으로 아래 고정 IP·Docker·SSH/HTTPS 방화벽 구성을 수행했다. 기본 구성 단계에서는 실제 앱/DB 배포·데이터 이전·DNS/도메인 구매·유료 플랜 전환·GitHub 반영을 하지 않았다. 이후 README와 연결 문서의 GitHub 반영은 별도 요청으로 진행한다.

## 기본 구성 완료 기록 — 2026-10-18

| 항목 | 완료 결과 |
|---|---|
| 고정 IPv4 | 기존 서울 VM에 생성·연결 완료. 실제 자원명/주소는 Git 제외 운영 기록에 보관 |
| Docker | 공식 stable apt 저장소로 Engine/CLI **29.9.1**, Compose plugin **5.6.0** 설치 |
| 실행/자동 시작 | docker/containerd 모두 active·enabled. 포트/볼륨/네트워크 없는 일회 `hello-world` 실행과 자동 정리 확인 |
| SSH TCP22 | 모든 IPv4/IPv6 일반 CIDR 허용을 제거하고 **Lightsail 브라우저 SSH IPv4 전용**으로 제한. 제한 후 별도 새 연결·명령 실행 성공 |
| HTTP/HTTPS | 기존 TCP80 유지, **TCP443 추가**. 두 규칙 모두 IPv4/IPv6 허용. UDP443·앱/DB·원격 Docker TCP API는 열지 않음 |
| 외부 시험 | PC→새 서버 TCP22 한 번의 직접 연결은 시간 초과. 브라우저 SSH 재접속과 저장된 규칙을 함께 확인한 보조 증거 |
| 상태 경계 | UFW/sshd/사용자·docker 그룹 권한을 변경하지 않음. 실제 Caddy/인증서·앱/운영 DB는 아직 없음 |

고정 IP는 생성 직후 연결했으며 연결 중 별도 IP 요금은 없다. 추가 EC2/RDS/LB·snapshot·bucket·도메인 결제나 플랜 전환은 하지 않았다. 관리 PC 직접 SSH CIDR은 추가하지 않았으며 필요하면 별도로 관리 IP를 확정한다. 현재 관리 경로는 검증한 Lightsail 브라우저 SSH다. [고정 IP 요금](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html), [Docker 공식 설치](https://docs.docker.com/engine/install/ubuntu/).

상세 실행·명령·버전·증빙 화면은 Git 제외 `.local/lightsail-bootstrap-2026-10-18.private.md`와 `.local/lightsail-bootstrap-2026-10-18-proof/`에 보존했다. OS 로그인 배너의 보안 업데이트 안내를 확인했으며 운영 전에 패치/재시작 범위를 검토해야 한다. 이번에는 OS 전체 upgrade나 재부팅을 수행하지 않았다. 다음은 OS 보안 패치 계획과 도메인/DNS·외부 백업 비용 선택, 합성 배포·복구 검증이며 실회원 데이터 이전은 기존 조건/승인 경계를 따른다.

## 구성 전 실제 읽기 전용 점검 결과

아래는 고정 IP/Docker/방화벽 변경 **전**의 관찰 기록이다. 현재 상태는 위 최신 완료 결과를 따른다.

계정별 금액/날짜·알림·접속 주소와 상세 증거는 Git 제외 `.local/lightsail-readonly-audit-2026-10-18.private.md`에 보관한다. 계정번호·수신 이메일·SSH 키·토큰은 공개 문서에 복사하지 않는다.

| 항목 | 실제 확인 |
|---|---|
| Billing/크레딧 | Free Plan 유지·잔액/만료 표시 확인. 가입/활동 크레딧 **모두 Amazon Lightsail 적용 가능**. Bills 서비스 데이터는 준비 중이라 실제 사용료와 크레딧 상계는 아직 미검증 |
| 플랜 기간 | 콘솔은 남은 일수를 표시하며 정확한 종료 timestamp는 확인한 화면에 없음. 크레딧 만료 표시와 플랜 종료를 구분 |
| Budgets | 월별 예산·수신 이메일 인증·알림 3개 확인. Credit/Refund를 제외한 비용을 감시하며 자동 차단 작업 없음 |
| 기존 VM | 서울/AZ A, Running, 범용 2 vCPU·4GB·80GB dual-stack |
| OS/플랫폼 | Ubuntu **24.04.4 LTS**, **x86_64**, CPU 2개. 기존 amd64 이미지와 일치 |
| 초기 자원 | RAM 약 3.7GiB 중 available 3.3GiB, 루트 ext4 77G 중 available 75G. swap 0. 단일 시점이며 앱 부하 검증 아님 |
| Docker/Compose | 현재 PATH에 `docker` 명령 없음, service/socket inactive. 설치/기동 필요 |
| IP/클라우드 방화벽 | 고정 IPv4 **미연결**. TCP22/80은 모든 IPv4·IPv6 허용, **TCP443 규칙 없음** |
| OS/앱 포트 | UFW inactive. SSH22·내부 DNS 외에 TCP80/443/3000/8080/5432 listener 없음. DOCKER-USER 체인 부재만으로 전체 OS 방화벽 부재를 단정하지 않음 |

읽기 점검 직후의 후속 과제였던 고정 IP·SSH 제한·TCP443·Docker는 별도 승인으로 위와 같이 완료했다. 추가 비용·유료 플랜 전환·실데이터 이전/개방은 기존 승인 경계를 유지한다. 크레딧 사용 초기 표시는 집계 지연과 구분하고 Bills 데이터가 준비된 뒤 실제 상계를 확인한다.

## 2026-10-18 당시 결정과 확인 범위

- 사용자가 생성한 **서울 Lightsail 1대**를 우선 활용한다. Ubuntu 24.04 LTS, 2 vCPU·4GB RAM·80GB SSD, IPv4/IPv6 플랜이며 실행 중 상태를 콘솔에서 확인했다. 한국 접속 지연을 유지하면서 Free Plan과 기존 크레딧을 활용해 추가 비용을 줄인다.
- `bandjeongeum.cloud`는 **아직 미등록**이다. 등록업체·이름 가용성·첫해/갱신 결제액·DNS 제공자를 확정하지 않았다.
- 고정 IP 연결·Docker/Compose 설치·SSH/443 방화벽은 완료했다. 도메인은 미등록이며 실제 공개 HTTPS·앱 배포는 아직 수행하지 않았다.
- 계정별 초기 현황은 Git 제외 `.local/lightsail-deployment-plan.private.md`에, 실제 콘솔/SSH 관찰은 별도 날짜별 비공개 점검 기록에 보관한다. MFA는 사용자 직접 로그인 절차로 처리했으며 비밀키·암호를 수집하지 않았다.
- 별도 EC2·RDS·로드 밸런서·Lightsail Container Service·추가 디스크를 기본 구성에 넣지 않는다. 추가 비용·유료 플랜 전환·도메인 결제·실제 데이터 이전/서비스 전환은 설명과 사용자 확인 후 수행한다.

## 재사용하는 릴리스와 자원

새 앱 배포 기준은 [발행 기록](PRODUCTION_IMAGE_RELEASE.md)의 **rc.3 / `a00a791` exact digest** 3개와 Compose의 고정 PostgreSQL digest다. 아래 rc.2 발행 결과는 기존 합성 QA 원본의 이력으로 보존하며 새 앱 배포 기준과 구분한다. 현재 공유 secret source의 소비자별 권한을 해결하는 작업은 [적용 계획](PRODUCTION_INSTALL_PLAN.md)의 후속 구현·합성 검증 조건을 따른다.

[PR #24](https://github.com/developer-sw/jeongeum-band-scheduler/pull/24)와 [PR #25](https://github.com/developer-sw/jeongeum-band-scheduler/pull/25)는 main에 병합됐다. [main CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39024812366)는 Backend 368건·Frontend 334건·합성 컨테이너 검사 57개·Node 계약 38건·PowerShell export 15항목 및 빌드/타입/lint를 통과했다. [발행 39025711724](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39025711724)에서 `v1.0.0-rc.2` 공개 이미지 3개와 최종 산출물 검증을 완료했다. [릴리스 기록](PRODUCTION_IMAGE_RELEASE.md)의 digest를 그대로 사용한다.

이미지는 Linux `amd64` 단일 플랫폼이며 기존 서버의 `x86_64`를 확인했다. 운영 VM에서는 빌드하지 않는다. 공개 이미지 pull에는 별도 GHCR PAT가 필요하지 않다. 기존 [운영 Compose](../deploy/compose.production.yaml)·Caddy 설정·운영 프로필·고정 PostgreSQL 17 이미지를 재사용하고 새 업무 구현이나 migration을 추가하지 않는다.

| 구성 | 현재 메모리 제한 | 실행 경계 |
|---|---:|---|
| PostgreSQL | 768MiB | 새 전용 영속 볼륨, 내부 DB 네트워크 |
| Spring Boot | 1280MiB | 단일 Backend, JVM heap 약 640MiB 상한 |
| Next.js | 640MiB | 단일 Frontend, Node old-space 384MiB |
| Caddy | 128MiB | 호스트 TCP 80/443만 publish |
| 합계 | **2816MiB = 2.75GiB** | 실제 총 RAM 약 3.7GiB 기준으로 상한 밖 약 0.95GiB. OS·Docker·백업 등도 이 여유를 사용하므로 실측 필요 |

이는 초기 자원 설정이다. 실제 VM의 가용 RAM·버스트 CPU·예약 지연·재시작/OOM·백업 동시 실행·디스크 증가를 측정해야 안정 운영을 판정할 수 있다. [Docker Ubuntu 지원](https://docs.docker.com/engine/install/ubuntu/).

## 월 비용과 크레딧 적용

현재 Paid Plan에서는 기존 적격 크레딧을 대상 요금에 우선 적용하며 초과·만료·제외분은 청구될 수 있다. 예산은 지출 차단 장치가 아니다. 아래 표의 후보·미등록·미선택 설명은 **2026-10-18의 크레딧 차감 전 USD 사전 비교 기록**이다. 이후 합성 S3와 운영 도메인의 실제 준비 결과는 위 후속 기록을 따르며, 이 표가 추가 자원 생성을 뜻하지 않는다. 도메인 연간 결제·세금·환율/결제 수수료·초과 사용량은 별도다.

| 항목 | 기본/선택 | 예상 비용과 조건 |
|---|---|---|
| 기존 Linux/IPv4 포함 4GB 인스턴스 | 기존 자원 | **$24/월**, 4TB 전송량 포함. 시간 과금·월 상한이며 정지 중에도 삭제 전까지 소모 |
| Lightsail 고정 IPv4 1개 | 연결 완료 | 인스턴스 연결 중 $0. 미연결 1시간 초과 시 **$0.005/시간** |
| Lightsail DNS | DNS 후보 | 최대 6개 zone·월 300만 질의 범위 무료, 초과 **$0.40/100만 질의** |
| Caddy 자동 HTTPS·Docker Engine/Compose | 기존 방식 | 별도 라이선스/인증서 구입비 $0. 로드 밸런서·ACM 유료 부가 구성을 추가하지 않음 |
| 서버 밖 암호화 논리 백업 | 운영 개방 전 필요 | 저장 위치 미확정. 승인 후보인 Lightsail Object Storage는 **$1/월**(5GB·송신 25GB), 초과 별도. 기존 R2안도 미확정 |
| Lightsail snapshot | 선택·승인 필요 | **$0.05/GB-month**. 20GB 보관 $1, 80GB 보관 $4 예시. 누적 증분·보존 개수에 따라 증가하며 80GB가 전체 백업 비용 상한은 아님 |
| 도메인 등록·갱신 | 별도 구매 필요 | 등록업체의 현재 연간 결제액 확인 후 승인. Route 53 등록/이전에는 AWS 크레딧 사용 불가 |
| Route 53 hosted zone·LB·RDS·추가 EC2 등 | 기본 구성 제외 | 필요한 AWS 추가 컴퓨팅/관리형 DB 자원 없음. 무료 DNS를 선택하면 Route 53 hosted zone도 불필요 |

근거: [Lightsail 가격](https://aws.amazon.com/lightsail/pricing/), [과금·IP·DNS·snapshot](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html), [Object Storage](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-buckets.html), [Caddy 자동 HTTPS](https://caddyserver.com/docs/automatic-https). 신규 고객의 예전 Lightsail 3개월 무료 체험을 추가 할인으로 계산하지 않는다.

최소 서버 구성은 **$24/월**이다. $1 외부 백업을 선택하면 **$25/월**, 여기에 20~80GB snapshot 보관 예시를 더하면 **$26~29/월**이다. snapshot은 외부 논리 백업과 실제 복원 시험을 대체하지 않는다. 외부 백업 저장소를 선택·구현·복구 검증하기 전에는 $24 구성을 운영 준비 완료로 표시하지 않는다.

서울 인스턴스는 전송량 초과 송신에 **$0.13/GB**가 적용될 수 있다. 수신도 전송량 allowance 계산에 포함되나 초과 수신 자체는 과금하지 않는 규칙을 따른다. [전송량 규칙](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html).

**크레딧 적용 판정:** Lightsail 공식 안내와 실제 계정의 두 크레딧 eligible service 목록에서 Amazon Lightsail 적용을 확인했다. 잔액/표시 만료도 콘솔에서 확인했으나 현재 Bills에는 서비스별 사용료가 없어 **실제 상계 행 확인은 남아 있다**. 사용 크레딧의 초기 표시만으로 요금 누적이 없다고 판단하지 않는다. 크레딧으로 등록/이전 도메인·세금을 충당한다고 계산하지 않는다. [AWS 크레딧 조건](https://aws.amazon.com/awscredits/), [Route 53 등록 조건](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/domain-register.html).

당시 도메인은 미등록이었으므로 비교표의 합계에 확정 결제액을 넣지 않았다. 기존 사전 비교표의 `.cloud` 할인은 당시 참고값이다. 이름 가용성·프리미엄 여부·첫해와 갱신가·세금·자동 갱신을 구매 전에 보고하는 원칙은 유지한다. 외부 등록업체에서 구매한 이름도 Lightsail DNS에 연결할 수 있다. Route 53 등록은 hosted zone을 자동 생성해 추가 비용이 생길 수 있으므로 별도 결제·자원 생성을 자동 실행하지 않는다.

## Free Plan 기간과 운영 제약

**아래는 Paid 전환 전의 검토 이력이다.** 현재는 2026-10-25 일반 Paid Plan으로 전환을 완료했으며 이 절의 Free 기간·전환 미실행 설명을 현재 계정 상태로 읽지 않는다. 기존 크레딧의 적격 서비스·만료·청구 조건은 Paid 전환 후에도 별도로 관리한다.

Free Plan은 **가입 후 최대 6개월 또는 크레딧 소진 중 빠른 시점**에 끝난다. 크레딧별 표시 만료일이 플랜 종료를 연장하지 않는다. 가입일 기반 예상 상한·잔액별 사용 가능 개월 계산은 비공개 계획에 구분한다. 종료 시 서비스/데이터 접근을 잃고, 90일 보존은 서비스 연장 기간이 아니다. 계속 쓰려면 유료 전환이 필요하며 당시 검토에서는 전환하지 않았다. [AWS 플랜 문서](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html).

현재 공식 FAQ에는 2026년 10월 가입 경험에 관한 일부 6개월 크레딧 안내가 있고 약관·다른 FAQ 항목에는 12개월 안내가 있다. 사용자 콘솔 표시값을 임의로 수정하지 않고, **계정의 실제 플랜 종료일과 각 크레딧 만료/적용 조건**을 확인한다. [Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/), [Free Tier 약관](https://aws.amazon.com/free/terms/).

AWS 약관은 Free Plan이 평가 목적이며 민감 데이터 처리에 사용하지 말라고 명시한다. 따라서 먼저 합성 데이터로 설치/HTTPS/복구를 검증한다. 실회원 명단·계정·Push 구독을 옮기기 전 AWS에 이 서비스의 적용 범위를 확인하거나 별도 승인받은 유료 플랜 운영안을 검토한다. 이 문서만으로 모든 개인정보가 약관상 같은 분류라거나 유료 전환이 반드시 필요하다고 단정하지 않는다. **적용 범위 확인과 실제 이전 승인이 필요하며 자동 업그레이드는 하지 않는다.**

Savings Plans·Reserved Instances·일부 Marketplace 등은 Free Plan에서 제한된다. AWS Organizations 가입·Control Tower 등 특정 조작은 유료 전환/크레딧 변경을 유발할 수 있어 이번 구성에 포함하지 않는다. 콘솔에 `Upgrade`·추가 결제가 나오면 중단하고 보고한다. 월 예산 이메일 알림은 지출을 자동 차단하는 설정이 아니며 집계/알림 지연이 있다. 크레딧 차감 전 비용과 잔액을 함께 보고, Budgets의 크레딧 포함 여부도 확인한다. [플랜 제한](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html), [Budgets 집계/지연](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-best-practices.html).

## 다음 실행 순서

1. **현재 승인 범위의 사전 점검:** 기존 서버 자원·Docker 네트워크·컨테이너/볼륨·80/443·설정 소유권을 읽기 전용으로 확인하고 [적용 계획](PRODUCTION_INSTALL_PLAN.md)에 기록한다. secret 내용·실제 DB는 열람하지 않는다.
2. **런타임 권한 방식 확정:** 공유 앱 DB secret을 PG init과 Backend가 각각 읽을 수 있도록 소비자별 사본·명시적 Compose 참조 안을 검토한다. 원본 5개 논리값을 보존하며 후속 구현·합성 Linux 검증 전에는 실제 secret을 설치하지 않는다.
3. **운영 백업 준비:** 현재 synthetic-only 백업을 운영 DB 보호로 확대하지 않는다. 별도 운영 DB용 권한·백업·독립 복원·감시를 구현·검증하고 최종 데이터 이전/공개 전에 성공을 확인하는 계획을 승인받는다.
4. **승인 후 순차 적용:** rc.3 exact pins·새 프로젝트와 볼륨·worker/Push OFF를 확인하고 승인된 최종 백업/쓰기 동결·복원·내부 앱 검증을 순서대로 진행한다. 기존 준비 Caddy를 유지하며 전체 Compose를 일괄 기동하지 않는다.
5. **별도 공개 전환:** 기존 edge와 신규 Caddy의 80/443 인계, 운영 origin 로그인·Push 재구독, worker의 단계적 활성화를 승인 후 수행한다. 새 쓰기 이후에는 최신 운영 데이터를 보존한 별도 복구 계획을 사용한다. 추가 자원·비용은 먼저 보고한다.

### 초기 계획 당시의 실행 순서 — 2026-10-18

1. **읽기 전용 계정/서버 점검:** Free Plan 유지·플랜 종료일·크레딧별 Lightsail 적용과 실제 Bills·예산 집계 조건을 확인한다. 기존 VM의 architecture/디스크·Docker 유무·볼륨·IP 연결·IPv4/IPv6 방화벽만 확인한다. 루트 MFA는 유지하고 장기 루트 API 키를 생성하지 않는다. SSH host key와 기본 키 접근은 비공개 경로에서 확인하며 개인 키 내용을 수집/출력하지 않는다.
2. **비용 선택 확정:** 도메인 결제액과 등록업체/DNS, 외부 백업 저장소·보존량, snapshot 사용 여부를 보고한다. 데이터 분류/Free Plan 조건도 해결한다. 신규 유료 리소스·유료 플랜 전환·도메인 결제는 승인 후에만 수행한다.
3. **기존 VM 준비 — 기본 구성 완료:** 고정 IPv4 연결·공식 Docker/Compose 설치·TCP80/443 IPv4/IPv6 허용·브라우저 SSH 전용 제한과 새 접속을 확인했다. 현재 일반 관리 IP CIDR은 없으며 직접 SSH가 필요하면 별도 확정한다. OS 보안 업데이트 계획·운영 secret/네트워크·실제 앱 부하 검증은 남아 있다.
4. **DNS/합성 기동:** 도메인 구매 승인 후 확인한 고정 IPv4에 A 레코드를 연결한다. 실제 IPv6 경로 확인 전 AAAA는 만들지 않는다. Caddy 직접 TLS(DNS-only)로 시작하며 Cloudflare Proxy는 선택 사항이다. `rc.2` digest와 저장소의 mount용 Caddy/init 파일을 함께 전달하고 `--no-build` 실행 경로를 사용한다. Git 밖 운영 설정·secret 파일 권한·네트워크 충돌·새 QA 볼륨을 점검하고 합성 데이터로 검증한다.
5. **백업·복구와 전환 제안 준비:** 외부 암호화 논리 백업·다운로드/checksum·키 회수·새 독립 DB 복원·행/관계/시퀀스/Flyway 대조를 검증한다. RPO/RTO·보존 주기는 기존 기술 제안이며 새 확정 정책으로 취급하지 않는다. 점검 창·쓰기 차단·실제 데이터 이전/공개 전환 절차와 롤백 조건을 제시하고 사용자 확인을 받는다.
6. **승인받은 실제 이전/개방:** 새 최종 V12 백업 이후 원본 로컬 DB·Docker 볼륨을 보존한 채 새 운영 DB에 복원한다. 회원/관리자·예정 권한·명단ID·팀/팀장·곡·예약/점유/한도·이력·세션·시퀀스·Flyway를 대조한다. 초기 관리자 재생성·기존 migration 강제 재적용을 하지 않는다. 전환 후 실제 쓰기 원본은 하나로 유지한다.
7. **운영 origin 검증/활성:** 기존 [데이터/origin 이전 순서](DEPLOYMENT_PREFLIGHT.md#6-기존-데이터를-보존하는-이전-순서)를 따른다. 네 worker와 Push OFF로 데이터를 대조하고 과거 구독/대기 작업 정리 범위를 확인한다. 이후 유효한 운영 VAPID와 `JEONGEUM_PUSH_ENABLED=true`·네 worker OFF로 재기동한 뒤, 기존 ID로 재로그인·iPhone 새 설치·사용자 기기 재구독을 확인한다. 반복 날짜 개방→교환 만료→시작 계획 worker를 단계적으로 활성화하고 대기 작업/대상 구독을 검토한다. Push worker를 마지막에 켜고 PC/iPhone 실제 수신/클릭을 검증한 뒤 서비스를 개방한다. 백업·자원/비용·종료일을 관측한다.

Docker의 publish는 UFW 규칙을 우회할 수 있다. **UFW만으로 DB/앱 보호가 완료됐다고 판정하지 않으며** Compose의 3000/8080/5432 미공개와 Lightsail 방화벽, 실제 외부 접근 시험을 함께 확인한다. 현재 Caddy는 HTTP/3을 끄므로 UDP 443 추가 개방은 필요하지 않다. [Docker 방화벽 문서](https://docs.docker.com/engine/network/packet-filtering-firewalls/).

현재 인증은 PostgreSQL 기반 서버 세션이다. JWT secret을 새로 만드는 구성이 아니며 기존 인증을 유지한다. DB 관리자/앱 암호·VAPID·백업 복구 키·SSH 개인 키와 실제 `.env`는 Git/공개 로그에 넣지 않는다. 이 문서는 실제 준비/이전 완료를 주장하는 체크리스트가 아니다.
