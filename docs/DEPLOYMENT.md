# 개발 준비·배포·운영 계획

최초 작성: 2026-10-09 / 최신 사전 점검·주요 공개 가격 재확인: 2026-10-17 (Asia/Seoul)

최신 실제 실행: 운영 이미지 준비 PR #24를 main `e84c183`에 병합하고 main CI 4개 job을 통과했다. 첫 이미지 3개는 Public으로 발행돼 비공개 검사에서 중단됐고, 설명 후 사용자가 공개 사용을 승인했다. 명시적 visibility 선택과 조기 검사 보완 후 새 버전의 최종 발행을 검증한다. 기존 개발 DB/서버와 package·태그는 보존하며 서버 배포는 하지 않는다. [PRODUCTION_IMAGE_RELEASE](PRODUCTION_IMAGE_RELEASE.md)를 따른다.

최신 후속: 운영 컨테이너 PR #23은 main `911c181`에 병합됐고 main CI의 Backend 368건·Frontend 334건·합성 검사 57개가 성공했다. 현재는 [운영 이미지 발행 준비](PRODUCTION_IMAGE_RELEASE.md)를 진행한다. GHCR의 비공개 package·검증 이미지 archive·SHA/version tag·최종 digest·수동 준비/발행 분리를 구현하며, 실제 발행·서버 구매·DB 이전·배포는 실행하지 않는다. 아래 초안 PR 예정 표시는 이전 단계 기록이다.

후속 완료: 운영 Docker·Compose·Caddy 구현과 합성 DB의 실제 HTTPS·권한/예약·재시작·별도 DB 복원 검증 57개를 통과했다. 새 실행 자산·QA 명령·최종 증빙과 남은 실제 운영 검증은 [PRODUCTION_CONTAINERS](PRODUCTION_CONTAINERS.md)를 따른다. 기존 개발 DB·실행 서버를 보존하고 QA 자원을 정리했다. 이후 승인된 GitHub 반영의 원격 CI 결과는 해당 초안 PR에서 확인한다. 구매·실배포는 하지 않았다. 아래 사전 점검 표의 자산 미구현·미실행 표시는 그 점검 시점의 상태다.

## 1. 범위와 현재 상태

이 문서는 약 40명의 정음 동아리를 위한 개발환경·운영 구성 계획이다. 2026-10-17 현재 관리자 가입 전/후 지정·V12·후속 UI까지 PR #21로 main에 병합했고 PR #22로 README를 갱신했다. 기능 검증은 Backend 368건·Frontend 334건이며 기능 PR과 병합 main CI도 성공했다. 최신 소스 감사·환경변수 계약·기존 데이터/origin 이전·배포 전 통과 조건은 [DEPLOYMENT_PREFLIGHT](DEPLOYMENT_PREFLIGHT.md)를 따른다.

사용자는 **한국 접속 지연·운영 여유 우선**을 확정했다. 이에 서울 4GB 단일 VPS를 우선 기술 권고로 삼는다. 사전 점검은 구성안 문서 작성까지였고, 후속 승인으로 운영 컨테이너 구현·합성 검증과 GitHub 반영을 진행했다. 서버 생성·도메인 구매·실제 DB 이전/배포는 실행하지 않았다. 저장소 밖 계정의 현재 소유/구매 상태를 조회하지 않았으므로 도메인·서버 보유 여부와 최종 비용은 실제 준비 때 확인한다.

확정 운영 정책은 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md), 기술 구조는 [ARCHITECTURE.md](ARCHITECTURE.md)를 따른다. 이 문서의 금액은 **견적 비교**, 서버·백업·로그 설정은 **기술 설계안**이다. 도메인 구매 가능성·서버 재고·성능을 확인한 배포 완료 상태를 뜻하지 않는다.

로그인은 사용자 확정에 따라 마지막 접속 후 90일 동안 유지하고 접속 시 연장한다. 운영에서는 `SESSION`의 HTTPS `Secure`·`HttpOnly`·`SameSite=Lax`와 프록시의 `Set-Cookie` 전달을 유지한다. 같은 운영 origin에서 서버를 재시작할 때 PostgreSQL 세션을 보존한다. localhost에서 새 도메인으로 이전할 때는 기존 쿠키가 전달되지 않으므로 계정 재생성 없이 재로그인한다. 이번 문서 갱신은 새 migration이나 기존 DB 초기화가 필요 없으며 운영 브라우저 쿠키는 운영 origin에서 검증한다.

아래 표는 사전 점검 당시 소스와 기존 검증 기록을 대조한 상태다. 그 점검에서 도구 버전·실제 DB 상태·VM 성능을 재시험한 것은 아니며, 후속 컨테이너 구현과 합성 검증 결과는 위 최신 기록을 따른다.

| 항목 | 점검 결과 | 다음 단계 |
|---|---|---|
| 프로젝트·Git | `main` `bf2a474`, PR #21/#22 병합·동일 기능 CI 성공 | 운영 준비는 별도 브랜치·승인 범위에서 진행 |
| Backend | Java 21·Spring Boot 4.1.1, 20 suites·368건·JAR 성공 | 운영 이미지·런타임 자원·프록시 경계 검증 |
| Frontend | Node 24 계열·Next.js 16.4.0, 23파일·334건·타입/lint·build 성공 | 운영 이미지·내부 API origin·HTTPS 캐시/보안 헤더 검증 |
| PostgreSQL | 17.11·V1~V12 적용/기존 데이터 보존은 이전 검증 기록 | 전환 직전 새 V12 백업·분리 복원·비공개 DB 구성 |
| 모바일·Push | 합성 HTTPS의 PC/iPhone 공지·iPhone 시작 알림·종료 처리 확인 | 새 origin·구독/키 전환·Android·운영 설정 확인 |
| 도메인·서버 | 기존 후보 도메인/클라우드·R2를 이번 작업에서 구매·생성하지 않음 | 보유·가용성·결제액 확인 후 별도 실행 |
| 운영 자산 | 운영 Dockerfile·Compose·Caddy·이미지 발행·외부 백업 자동화 없음 | 구매 전 산출물 구현·합성 통합 검증 권고 |

도구 설치, 로컬 실행 검증과 실제 애플리케이션·운영 준비를 구분한다. 현재 DB 환경 검증을 운영 성능·업무 기능 검증으로 간주하지 않는다.

## 2. 일정과 운영 기간

| 시점 | 목표 |
|---|---|
| 2026-10-23 | 구현 시작 목표 |
| 2026-10-30 | 첫 실제 운영 시작 목표 |
| 2026-12-11 | 이번 운영 종료 목표 |

이 표는 초기 목표 기록이다. 10/23의 구현 시작 가정과 달리 핵심 기능은 이미 구현했으며 날짜를 근거로 미완료 운영 검증을 생략하지 않는다. 10/30~12/11은 정확히 6주이고, 10/23부터 서버를 유지한다면 약 7주이므로 비용 비교는 여유를 둔 2개월로 계산한다. 실제 자원 생성일·전환 점검 창과 첫 운영일은 구매·배포 전에 확인한다.

첫 운영 전에는 확정 기능의 핵심 흐름, DB 동시성·권한·한도·교환 검증, 알림 작업 복구, 실제 모바일 사용, 외부 백업 복원을 확인한다. 목표 일정 때문에 승인된 변경·교환·공지·Push·알림함 기능을 임의로 운영 후 기능으로 미루지 않는다.

## 3. 로컬 개발환경 준비 순서

1. 설계 문서와 개발 규칙을 검토하고 구현 시작 범위를 승인한다.
2. Windows에서 Git, JDK 21, Node.js LTS, IDE의 동작을 확인한다. Gradle은 프로젝트 Wrapper를 사용하고 전역 Gradle 설치를 전제로 하지 않는다.
3. Docker Desktop의 WSL 2 backend와 Linux engine을 정상화한다. PostgreSQL과 Testcontainers를 사용할 수 있는지 확인한다.
4. 독립 Git 저장소와 GitHub 저장소를 준비하고 안전한 인증 방식으로 연결한다. main은 안정 상태로 관리하고 기능 단위 브랜치·PR·commit을 사용한다.
5. backend, frontend, docs를 가진 단일 저장소 골격을 생성하고 세부 버전을 고정한다. build artifact와 secret은 Git에서 제외한다.
6. 개발용 PostgreSQL을 Docker Compose로 실행하고 Spring·Next.js는 IDE/개발 서버로 실행한다. 개발 중 앱 재시작·디버깅을 편하게 한다.
7. Flyway migration, API·보안·프론트 연결, 핵심 테스트·CI를 구성한 뒤 운영 컨테이너와 배포를 준비한다.

Windows와 WSL의 경로·인증·환경변수를 무의식적으로 혼용하지 않는다. 현재 프로젝트 위치를 유지하며 사용하는 실행 환경을 실행 문서에 명시한다. 로컬에서 PostgreSQL을 별도로 Windows 서비스로 중복 설치할 필요는 없다.

[Docker Desktop Windows 조건](https://docs.docker.com/desktop/setup/install/windows-install/), [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html), [Testcontainers 실행 환경](https://java.testcontainers.org/supported_docker_environment/)

## 4. 운영 컨테이너 구성

첫 운영은 단일 VPS와 Docker Compose 구성을 우선 검토한다.

| 구성 요소 | 실행·데이터 경계 |
|---|---|
| Caddy | 외부 HTTP/HTTPS 진입점, reverse proxy, 인증서 데이터 지속 저장 |
| Next.js | 운영용 프론트 이미지, 개인화 응답은 공용 캐시 제외 |
| Spring Boot | 운영용 백엔드 이미지, API·작업자·알림 스케줄 처리 |
| PostgreSQL | 별도 컨테이너, DB 데이터 지속 볼륨, 외부 공개 포트 없음 |
| 백업 작업 | DB 논리 백업을 암호화해 외부 R2로 전송, 상태 확인 |

- 애플리케이션은 Docker 이미지로 교체하고 DB 볼륨은 독립 유지한다. 앱 컨테이너 내부 임시 경로에 DB를 저장하지 않는다.
- 첫 운영의 DB는 VPS에서 직접 운영한다. 별도 관리형 DB 요금을 기본 비용에 추가하지 않으며, 직접 DB 운영·복구 책임이 따른다.
- 빌드는 가능한 한 개발환경·CI에서 수행하고 운영 서버에는 검증한 이미지를 전달한다. 작은 VPS에서 Next.js·Gradle 빌드와 서비스 실행이 동시에 메모리를 쓰게 하지 않는다.
- 초기에는 backend 한 인스턴스, frontend 한 인스턴스다. DB outbox·세션·잠금은 메모리에만 의존하지 않는다.
- CPU·메모리 제한, 로그 회전, 재시작 정책, health check, DB readiness를 구성한다. DB 준비 전에 migration·API를 실행해 기동 실패를 반복하지 않게 한다.
- 서버는 단일 장애 지점이다. 고가용성을 주장하지 않고 외부 백업·복구·상태 확인으로 대응한다.

## 5. 도메인·HTTPS·네트워크

기존 도메인 후보는 bandjeongeum.cloud다. 이전 기록에서는 미구매였으며 이번 점검에서 현재 보유·가용성은 확인하지 않았다. 사용자 브라우저는 하나의 origin을 사용하고 /api/v1 및 하위 경로는 Spring으로, 화면·정적 파일은 Next.js로 전달한다.

Cloudflare는 DNS와 선택적 Proxy에 활용하고 Caddy는 origin HTTPS와 경로 전달을 맡는다. 초기 DNS-only에서 Caddy 인증서·HTTPS를 먼저 확인한 뒤 Proxy를 적용하는 순서를 권고한다. Proxy 적용 시 origin 인증서를 검증하는 Full (strict)·신뢰 IP·개인화 캐시 제외를 함께 검증한다. 실제 인증서 발급·갱신은 아직 시험하지 않았다.

- 외부 공개는 HTTP/HTTPS와 제한된 관리 접속만 허용한다. PostgreSQL과 내부 앱 포트를 인터넷에 직접 공개하지 않는다.
- HTTPS 자동 리다이렉트·Secure 쿠키·CSRF를 함께 확인한다.
- reverse proxy는 외부에서 온 forwarded header를 그대로 신뢰하지 않게 구성하고 Spring은 신뢰하는 proxy 경계를 기준으로 원래 scheme을 인식한다.
- 로그인·초대·알림함·개인화 HTML/RSC·인증 API는 CDN 캐시에서 제외한다. 정적 파일 캐싱이 유럽 서버의 예약 API 지연까지 없애는 것은 아니다.
- 이름 가용성·일반 도메인 여부·결제 최종 가격·자동 갱신 정책은 구매 단계에서 확인한다.

[Caddy 자동 HTTPS](https://caddyserver.com/docs/automatic-https), [Cloudflare Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)

## 6. 2개월 비용 비교

무료 체험·신규 가입 크레딧을 지속 운영의 최소 비용으로 계산하지 않는다. USD 공개 요금을 기준으로 하며 아래 금액은 세금·환율·해외 결제 수수료·초과 트래픽·무료 범위를 넘는 백업·추가 서비스 비용을 제외한다.

Porkbun의 일반 .cloud 도메인 공개 가격은 첫해 할인 $3.88, 일반 등록·갱신·이전 $21.11이다. 도메인은 두 달만 구매하는 방식이 아니라 1년 등록 비용을 이번 운영 예산에 포함한다. 특정 이름이 일반 가격으로 구매 가능하다는 보장은 아니다. [Porkbun .cloud 공식 가격](https://porkbun.com/tld/cloud)

| 후보 | 서버 사양 | 월 서버+IPv4 | 2개월 서버+IPv4 | 도메인 첫해 포함 예산 |
|---|---|---:|---:|---:|
| Hetzner 유럽 CX23 | 2 vCPU / 4GB / 40GB | $7.09 | $14.18 | **$18.06** |
| Lightsail 서울 2GB | 2 vCPU / 2GB / 60GB | $12.00 | $24.00 | **$27.88** |
| Lightsail 서울 4GB | 2 vCPU / 4GB / 80GB | $24.00 | $48.00 | **$51.88** |

표는 Cloudflare DNS/Proxy의 무료 범위와 R2 Standard 외부 백업의 무료 저장·작업 범위 안에서 운영한다는 조건의 비교다. 무료 범위 내 실제 사용량은 운영 중 확인해야 한다. 예산은 월 가격 2회분이며 실제 서버 과금은 사용 시간·월 상한과 자원 종료 시점에 따라 달라진다.

### 6.1 Hetzner 유럽 후보

CX23의 2026-06-15 이후 공개 요금은 서버 $6.49/월, IPv4 $0.60/월이다. 4GB·2 vCPU·40GB 구성은 저비용 비교 후보다. 2026-10-17 상품 페이지는 unavailable로 표시했으며 실제 계정/region의 재고·결제액은 확인하지 않았다. 현재 사용자 우선순위에 따른 기본 권고는 서울 4GB다.

- 한국에서 로그인·예약·교환·알림 API의 응답 지연이 사용 가능한 수준인지 측정한다.
- 구매 가능성과 접속 지연이 허용되면 표의 후보 중 낮은 비용안이다. 전체 시장의 최저가나 구매 가능을 보장하지 않는다.
- 선택 사항인 Hetzner 자동 daily backup은 서버 가격의 20%다. 이를 추가하면 서버+IPv4+자동 백업은 약 $8.39/월, 2개월+첫해 도메인은 약 **$20.66**이다. R2 논리 백업 예산과 혼동하지 않는다.
- 저가 유럽 요금을 싱가포르 요금으로 제시하지 않는다. 싱가포르 CPX22 4GB의 공개 요금은 서버만 $30.99/월로 다른 가격이다.

[현재 가격 조정 공식 표](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/), [CX23 사양·상품 페이지](https://www.hetzner.com/cloud/cost-optimized/), [Primary IPv4 요금](https://docs.hetzner.com/cloud/servers/primary-ips/overview/), [자동 백업 요금](https://docs.hetzner.com/cloud/billing/faq/)

### 6.2 Lightsail 서울 후보

2026-10-17 재확인한 IPv4 포함 Linux/Unix 2GB는 $12/월, 4GB는 $24/월이다. 서울 region을 제공한다. Java·Next.js·PostgreSQL이 함께 실행되는 시나리오의 메모리·기동·알림·예약·백업 영향을 측정한다. 회원 약 40명만으로 특정 용량의 충분 여부를 확정하지 않는다.

- **최신 기술 권고:** 사용자 확정인 한국 지연·운영 여유 우선에 맞춰 서울 4GB를 기준으로 준비한다. 이는 이전 2GB 우선 기술안을 갱신하는 것이며 구매 승인이나 성능 보장은 아니다.
- 2GB는 검증을 거친 비용 절감 대안으로만 남긴다. 1GB를 초기 운영의 기본안으로 두지 않는다.
- IPv6-only 2GB $10/월, 4GB $20/월도 공개되어 있으나 배포·관리·외부 Push 등 연결 검증이 추가되므로 기본 표는 IPv4를 사용한다.
- snapshot은 별도 $0.05/저장 GB·월이다. 위 기본 표에 Lightsail 유료 snapshot을 포함하지 않았다.

[Lightsail 요금](https://aws.amazon.com/lightsail/pricing/), [서울 region 제공](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-regions-and-availability-zones-in-amazon-lightsail.html)

### 6.3 배포 후보의 결정 상태

사용자는 한국 접속 지연·운영 여유 우선을 선택했다. 공식 가격과 기존 기술 구조를 기준으로 Lightsail 서울 4GB를 우선 권고하고, 2GB·유럽 4GB는 비교 대안으로 남긴다. 공급자·최종 예산·실제 계정/재고·보유 도메인과 최종 구매는 아직 확정하지 않았다. 구체 구성과 후속 검증 게이트는 [DEPLOYMENT_PREFLIGHT](DEPLOYMENT_PREFLIGHT.md)를 따른다.

Railway는 배포 편의 후보로 남지만 메모리·CPU·volume 사용량 기반 실제 비용과 DB 운영·백업 책임을 따로 검증해야 한다. 관리형 PostgreSQL의 모든 운영 기능이 자동 제공된다고 가정하지 않는다. 아직 사용량을 측정하지 않았으므로 단일한 보장 월 금액을 이 표에 넣지 않는다.

## 7. DB 백업과 복원

R2 Standard의 현재 월 무료 범위는 10 GB-month 저장, Class A 100만 작업, Class B 1,000만 작업이다. Internet egress는 무료이며 Infrequent Access에는 무료 저장 범위가 적용되지 않는다. 저장량만 아니라 작업 횟수도 확인한다. [Cloudflare R2 가격](https://developers.cloudflare.com/r2/pricing/)

**기술 설계안:** 초기 일 1회 백업안에 더해, 최신 사전 점검에서는 매시간 암호화 논리 백업·시간별/일별/주별 보관과 RPO/RTO 목표 후보를 제안했다. 주기·보존 개수·복구 목표는 아직 사용자 미확정이며 [DEPLOYMENT_PREFLIGHT](DEPLOYMENT_PREFLIGHT.md)의 복구 절을 따른다. 운영 규모·실제 dump 크기·작업 횟수로 비용을 판정한다. private R2 bucket의 10GB 무료 범위가 과금의 자동 차단 장치인 것은 아니다.

- 백업 token은 해당 bucket의 필요한 권한만 부여하고 public bucket을 사용하지 않는다.
- 복호화 키는 서버와 Git에만 두지 않고 별도로 안전하게 보관한다. 서버 소실 시 키를 복구할 수 있어야 한다.
- DB dump가 완성되고 외부 업로드·검증이 성공한 뒤 완료 상태를 기록한다. 단순 파일 존재만으로 성공을 판정하지 않는다.
- 실패·누락·보관량 증가를 확인하고 알림 또는 운영 점검 항목으로 드러낸다.
- 운영 전 별도의 빈 DB에 실제 복원해 회원·팀·예약·알림 작업과 migration 호환성을 확인한다. 복원에 필요한 버전·secret 재설정·작업 재개 방법을 문서화한다.
- 하루 한 번 백업만 사용할 경우 서버 소실 시 마지막 성공 백업 이후 데이터를 잃을 수 있다. 최신 예약 보존이 더 중요해지면 백업 빈도·추가 복구 방식을 조정한다.
- 서버 snapshot은 설정·이미지 복구에 도움이 되지만 DB 논리 백업과 실제 복원 확인을 대체하지 않는다.

R2 계정·bucket·접근 token·암호화·스케줄은 아직 준비하지 않았다. 외부 서비스 생성이나 결제가 필요한 단계는 사용자 승인 범위 안에서 별도로 진행한다. [PostgreSQL SQL dump와 복원](https://www.postgresql.org/docs/current/backup-dump.html)

## 8. GitHub·CI 최소 범위

별도 저장소에서 main을 안정 상태로 유지하고 feature/fix 브랜치와 PR로 변경을 검토한다. GitHub 계정의 안전한 인증과 2단계 인증을 준비한다. branch protection·보안 기능은 선택한 공개 여부·플랜의 지원 범위 안에서 적용하고 이를 위해 유료 플랜을 기본 구매하지 않는다.

첫 CI는 PR과 main push에서 다음을 검증한다.

- 백엔드 컴파일·핵심 단위 테스트·PostgreSQL Testcontainers 통합 테스트.
- 중복 예약·동시 한도·묶음 원자성·변경·교환·권한·CSRF·알림 재계획 검증.
- 프론트 TypeScript 검사·lint·운영 빌드와 필요한 핵심 로직 테스트.
- migration으로 빈 DB를 구성하고 애플리케이션과 계약이 일치하는지 확인.

CI의 기본 GITHUB_TOKEN은 repository contents 읽기 권한으로 두고 작업에 필요한 권한만 추가한다. 실제 운영 DB·세션·초대·VAPID·R2 secret을 테스트 환경에 넣지 않는다. 외부 action은 신뢰하는 출처와 고정 revision을 사용하고 배포 권한은 검증 CI와 분리한다. 운영 배포는 검증 완료한 artifact를 선택해 수행하며 처음부터 자동 무승인 운영 배포를 전제로 하지 않는다.

[GitHub 인증](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github), [GitHub Actions 보안](https://docs.github.com/en/actions/reference/security/secure-use)

## 9. 첫 배포와 운영 준비 완료 조건

1. 빈 DB에서 Flyway·기동·health check가 성공하고 운영 secret이 로그·Git에 없다.
2. 초대 가입·로그인·로그아웃·CSRF·관리자/팀장 scope·탈퇴 권한 회수가 실제 API로 검증된다.
3. 개인·팀·반복 배정·동시성·하루 한도·취소·진행 중 생성·변경·교환의 확정 정책을 검증한다.
4. inbox·생성·변경·취소·교환·1시간 전 알림과 재시작 후 outbox·reminder 복구를 검증한다.
5. 실제 iPhone Safari·Android Chrome·PC에서 반응형·PWA 설치·Push·권한 거부·offline을 확인한다.
6. 외부 백업을 만든 뒤 빈 DB에 복원하고 복구 절차를 기록한다.
7. 실제 실행 메모리·디스크·요청 지연·백업 작업 영향을 확인하고 선택한 서버 크기를 판정한다.
8. 이전 앱 이미지로의 rollback과 migration의 호환 범위를 확인한다. 운영에 적용된 migration 파일을 수정해 rollback하지 않는다.

첫 운영 뒤에는 요청 오류·예약 충돌·작업 실패·메모리·디스크·백업 성공 시각을 확인한다. 로그인·예약 API에 대한 기본 시도 제한, 로그 회전, 의존성·OS 보안 업데이트도 운영 계획에 포함한다. 실사용 로그에는 필요한 식별자와 request ID만 남기고 비밀번호·token·초대 URL·Push 구독 키·DB dump 내용을 출력하지 않는다.

## 10. 운영 종료와 과금 정리

2026-12-11 운영 종료 시 예약 생성·작업 실행을 정리하고 최종 DB 백업을 외부에 저장해 복원 가능성을 확인한다. 필요한 데이터 보존·개인정보 정리 범위와 운영 결과를 문서화하고 README를 업데이트한다.

- 실제 데이터·최종 백업·복호화 키를 확보한 후 유료 서버를 종료·삭제한다.
- 서버를 정지하기만 하면 과금이 끝난다고 가정하지 않는다. 남은 IPv4·volume·snapshot·DB·R2 사용량·무료 범위 초과를 확인한다.
- 필요한 snapshot·백업은 보존 기간과 비용을 명시하고 불필요한 자원만 정리한다.
- 도메인의 자동 갱신·갱신 가격을 확인하고 다음 해 유지 여부를 결정한다. 첫해 할인 가격을 갱신 가격으로 기록하지 않는다.
- 남은 CI 배포 권한·서버·R2 token·Push 구독을 정리하고 운영에 쓰지 않는 접근을 회수한다.

[Lightsail 정지 중 과금](https://docs.aws.amazon.com/en_en/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html), [Hetzner 과금·남은 IP/백업](https://docs.hetzner.com/cloud/billing/faq/)

## 11. 아직 확인할 항목

- 최종 VPS 공급자·region·구매 가능성과 성능/지연 측정 결과.
- bandjeongeum.cloud의 구매 가능성·일반 가격 적용·갱신 설정.
- GitHub 비공개 저장소에 적용 가능한 branch protection 범위와 CI 사용량. 로그인·저장소 연결은 완료했다.
- 운영 서버의 Docker·PostgreSQL 구성. 로컬 engine·DB·Testcontainers 실행은 완료했다.
- 운영 컨테이너·배포 설정의 버전 고정. 초기 앱·개발 DB·검증 CI action의 버전은 고정했다.
- R2 계정·bucket·최소 권한 token·암호화 키 보관·실제 dump 크기·보존 설정·복원 결과.
- 선택한 서버 자원에서 memory·기동·예약·알림 작업·백업의 실측.
- 새 V12 최종 백업·원본 보존과 새 운영 DB 복원, 이전 origin의 Push 구독·대기 작업 처리·새 도메인 로그인/재설치·재구독 절차.
- 운영 이미지·Compose·Caddy·프로필/secret 주입·release/rollback·외부 백업 자동화와 실패 관측.

이 확인 항목은 이미 정한 예약·권한 정책을 다시 질문하거나 변경하기 위한 목록이 아니다. 실제 구현·계정 연결·구매·배포를 시작할 때 해결할 준비 항목이다.
