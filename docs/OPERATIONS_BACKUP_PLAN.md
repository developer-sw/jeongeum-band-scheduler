# 운영 계정·합성 정기 백업 검증

## 2026-10-26 후속 — 별도 운영 DB 백업 구현

사용자 요청으로 기존 synthetic-only 계약을 유지한 채 [운영 DB 백업·독립 복원·감시](OPERATIONAL_DATABASE_BACKUPS.md)를 별도 구현하고 합성 환경에서 검증했다. 새 source/target·설정·state·prefix·unit·지표를 사용하며 [운영 배포 예시](../deploy/operational-backup/README.md)는 기본 비활성이다. 실제 PostgreSQL dump·age 암호화/복원과 별도 엔진의 내용 대조를 확인했으나 AWS/S3·SNS·systemd는 실제 운영 대상으로 실행하지 않았다. 기존 합성 S3/IAM/타이머/경보와 16/25파일 복구본을 보존한다.

현재 서버의 정기 백업은 여전히 합성 DB 전용이다. 운영 경로의 권한·보존·지표/경보는 별도 승인·적용 확인이 필요하며 실제 운영 DB 보호의 완료를 주장하지 않는다. 사용자 승인으로 변경 검토·커밋·Push·main 대상 초안 PR·원격 CI 확인을 진행하며 최신 원격 결과는 해당 초안 PR의 동일 HEAD 검사를 따른다. 실제 대상 설치·단발 백업·독립 복원·감시·타이머 활성화는 이후 별도 승인 단계다. 아래 별도 구현 미완료 설명은 이 후속 요청 전 기록이다.

## 현재 기준 — 2026-10-25

일반 **Paid Plan 전환 완료**, **Basic 지원과 기존 적격 크레딧 유지**가 현재 상태다. 적격 요금은 크레딧으로 우선 상계하며 초과·만료·제외 요금은 청구될 수 있다. 예산 알림은 과금 상한이 아니다. AWS 재문의의 사람 답변은 별도로 검토한다. 아래 날짜별 Free Plan·Paid 미전환 기록은 당시 이력으로 유지한다.

기존 운영 설정 16파일의 비활성 원본·암호문을 보존한 뒤 소비자별 secret 권한 구현·합성 Linux 검증을 완료했다. 후속 승인으로 원본 값은 유지한 **새 25파일 설정 복구본**도 실제 age 암호화·Windows 독립 복원·Linux 권한 재현을 마쳤다. [권한 설정 복구 기록](RUNTIME_SETTINGS_RECOVERY.md)의 완료 범위·소유권 재현 한계를 따른다. 실제 서버 설정 설치·운영 DB 생성/백업·앱 기동·실회원 이전·공개는 후속이다.

**현 정기 백업은 synthetic-only로 새 운영 DB를 보호하지 않는다.** 기존 QA 복원 대상과 백업 원본의 차이·source guard·timer·IAM 범위를 유지한다. 운영 DB용 백업·정확한 원격 버전 검증·독립 복원·감시는 별도 구현·승인·검증 후 공개 전에 확인해야 한다. 원격 자원·네트워크·권한의 최신 점검 결과는 실행 담당자가 적용 계획에 기록한다.

## 2026-10-24 완료 기록

후속(2026-10-24): AWS 재문의 전송 후 사람의 답변을 기다리며 [비활성 운영 설정·암호화 복원](INACTIVE_PRODUCTION_SETTINGS.md)을 로컬에서 완료했다. 새 암호문은 PC에 보관하며 기존 합성 S3 자동 백업의 대상·timer·자격증명은 바꾸지 않았다. 운영 DB 정기 백업으로 전환한 결과가 아니며 실제 회원 데이터 이전·공개는 계속 별도 승인 대상이다.

검증일: **2026-10-24, Asia/Seoul**. 사용자 승인 후 서울 S3와 최소 IAM, 정기 백업·감시·이메일 경보를 **합성 DB에 한정하여 구성·검증**했다. 후속으로 AWS Basic 계정·결제의 Free Tier 일반 문의를 접수하고 실제 VM 재부팅·PG/준비 Caddy 자동 복구·원본 데이터/권한 보존을 확인했다. 당시 Free Plan을 유지했으며 지원 답변 검토는 남아 있었다. 실제 회원 데이터 이전과 앱 공개 전환은 별도 승인 후 진행한다. 계정번호·문의 번호·잔액·이메일·접속 주소·화면 증거는 Git 제외 비공개 기록에 둔다.

당시 사용자 확정 범위는 **Free Plan 유지**, **S3·주기/보존·이메일 시험**이며 후속 문의 접수·재부팅 복구 검증을 진행했다. 그 시점에는 일반 Paid 전환·유료 Support 가입·실회원 데이터 이전·앱 공개를 수행하지 않았다. 아래 일반 Paid 권고와 다른 저장소 비교는 사전 검토 기록이며 사용자 결정에 우선하지 않는다. 승인된 정책은 시간별 7일·주간별 28일·기본 Governance 잠금 7일, 백업/감시 월 $2 설계 목표(과금 상한 아님), 기존 예산 수신 이메일의 구독/시험 경보 사용이다.

같은 날 재부팅 결과: readiness fake **21개**, 실제 부팅 후 보존 **16항목**을 통과했다. 기존 컨테이너 5개·볼륨·설정 30파일·39개 테이블·29개 시퀀스·Flyway V1~V12·역할/인증/권한 지문을 보존하고 QA 앱 3개는 중지 상태다. 원래 hourly 타이머가 **16:00:18 KST** 실행되어 **16:00:24 KST** 새 S3 객체 exact-version 검증을 완료했으며 같은 주 주간본을 유지했다. 별도 source unit으로 기존 PG만 자동 시작하고 원래 `restart: no`를 유지한다. 해당 부팅 metric 서비스 성공·새 CloudWatch 지표/원래 설정/최종 OK, 인증서 검증 HTTPS200과 당시 FREE·ACTIVE를 확인했다. 추가 설정 3파일은 별도 4,356bytes 암호문으로 Windows 인증 복원·disk SHA/native ACL을 검증했고 기존 30파일 archive와 함께 보관한다. 단일 33파일 archive·자동 활성화·S3 설정 업로드는 아니다. 점검 시 자동 시작 중단 절차와 제한은 [OPERATIONS_REBOOT_VALIDATION](OPERATIONS_REBOOT_VALIDATION.md)을 따른다.

후속 설정 복구(2026-10-24): 명시적 allowlist의 **서버 준비·합성 QA 설정 30파일**을 age 암호화하여 PC의 새 private 폴더에 실제 복원했다. **26개 fake·21개 비공개 통합 검사**, 30파일 disk SHA/크기·Windows native ACL과 별도 격리 Linux 파일 도구의 mode/uid/gid 재현을 확인했다. 이는 기존 자동 DB 3파일 백업과 별개인 **수동 설정 백업**이며 실제 기술 자격증명을 포함한다. S3 새 업로드·설정 활성화·실제 DB 시작/이전은 하지 않았다. 같은 날 사용자가 **PC 밖 복구 개인키 직접 보관·사본 확인 완료**를 알렸으며 에이전트가 외부 사본을 직접 읽은 검증은 아니다. 실제 운영용 새 비밀값 생성/복구는 후속이다. 상세 범위와 현재 QA env/백업 DB 참조 차이는 [OPERATIONS_CONFIGURATION_RECOVERY](OPERATIONS_CONFIGURATION_RECOVERY.md)를 따른다.

## 2026-10-24 실제 완료 결과와 제한

| 항목 | 실제 확인 결과 |
|---|---|
| 계정·저장소 | Free Plan 유지. 서울 비공개 S3 1개, 공개 차단 4개·SSE-S3·Versioning·기본 Governance 7일과 시간별 7일/주간별 28일 Lifecycle 설정 확인 |
| 접근 권한 | 서버 전용 최소 IAM, CloudWatch 지표 1개·경보 1개·SNS 이메일 구독 1개 구성. 합성 경로 밖 업로드와 다른 지표 namespace는 거부 |
| 백업 원격 검증 | 실제 dump·age 암호화 후 Put이 반환한 정확한 VersionId로 Head/Get, 크기·SHA 대조. 중복 Put은 412, 버전 삭제·보존 변경은 거부. 이전 정상 객체 보존 |
| 서버 서비스·타이머 | 단발 백업 서비스와 5분 감시 서비스 성공, 두 타이머 enabled/active와 재시작 후 성공 상태·주간본 보존 확인 |
| 타이머 실제 호출 | 시험용 runtime `OnActiveSec=10s`로 systemd가 백업 서비스를 호출하여 새 성공 시각·시간별 S3 객체를 만든 것을 확인. 주간본은 유지. 시험 override를 삭제하고 원래 hourly 설정·활성 상태·다음 실행 시각으로 복구 |
| 실패·상태 복구 | 합성 DB를 실제 정지해 백업 실패와 이전 성공 상태 보존을 확인. 동일 manifest로 DB를 재기동하고 백업 복구 확인 |
| 경보·메일 | 격리한 빈 성공 상태로 stale 지표를 발행하여 ALARM, 정상 상태로 OK 복귀와 양쪽 SNS action 성공 확인. 사용자가 ALARM·OK 메일 모두 수신했다고 확인 |
| 미수신 평가 | 같은 경보만 일시적으로 데이터가 없는 시험 차원으로 변경하여 약 50.35초 후 missing datapoint를 breaching으로 평가한 ALARM 확인. 원래 차원·300초·Maximum·>90·missing=breaching·ActionsEnabled를 모두 복원하고 최종 OK·SNS action 성공 확인 |
| PC 독립 복원 | S3에서 검증한 암호문을 내려받아 복호화하고 새 PostgreSQL 17.11 엔진·볼륨·내부 네트워크에 복원. **10개 검사·19.477초**, 39개 테이블 전체 행 지문·29개 시퀀스·Flyway V1~V12·제한 역할/소유권 및 실제 PG 재시작 후 일치 확인 |
| 오프라인 회귀 | `scripts/production-backup.test.py`의 fake Docker/AWS/age/DB 검사 **27개 통과**. 실제 원격 통합 검증과 별도 결과 |

PC 복원은 PG만 실행했으며 앱/API·worker·실제 Push를 실행하지 않았다. PC의 Docker daemon은 개발 컨테이너와 공유할 수 있지만 복원 PG 프로세스·프로젝트·볼륨은 별개다. 복원 컨테이너는 정상 정지했고 신규 자원·암호문·비공개 증빙을 보존했다. 기존 개발 DB에는 접속하지 않았고 기존 컨테이너 metadata·볼륨 보존을 대조했다.

위 표의 초기 타이머 시험은 **축약 trigger 검증**이며 당시 실제 hourly 경계까지 기다린 시험이 아니다. 원래 QA Compose/컨테이너 restart policy는 `no`였고 이 초기 시험만으로 재부팅 자동 복구를 주장하지 않았다. **후속 실제 VM 재부팅·별도 source unit·원래 hourly 발화 결과는 위 최신 기록**으로 구분한다. 90분 경과 후 메일 전달 지연은 검증하지 않았다. 초기 미수신 시험은 추가 AWS 자원·서버 정지·시계 변경 없이 경보 평가만 시험한 것이다. 당시 종료 시 Free Plan, 원본 QA PG·준비 Caddy healthy, 기존 Caddy 시작 시각과 metric 서비스 성공을 확인했다.

새 복원 19.477초는 키/암호문이 PC에 준비된 뒤의 실행 구간이다. 전송·장애 탐지·키 회수·서버 재구축·서비스 공개까지의 전체 RTO 달성 결과가 아니다. 원본과 최초 실패 기록, 명령·계정별 값·수신 확인 증빙은 Git 제외 비공개 기록에 보존한다.

## 재사용하는 완료 결과

- 기존 서울 Lightsail 2 vCPU·4GB·80GB, Docker/Compose, 제한된 SSH, 도메인·HTTPS 준비를 유지한다. EC2·RDS·로드 밸런서·추가 VM·snapshot을 기본안에 추가하지 않는다.
- 이번 백업의 **QA 원본은 `v1.0.0-rc.2`, 소스 `8b24285`**로 만든 기존 합성 DB다. 새 앱 공개 전환의 이미지 기준인 PR #27 병합 main `a00a791b069f20943c5e956968e970bf138fa3dc`·`v1.0.0-rc.3` 발행/CI와 혼동하지 않는다. 운영 서버에서 소스를 재빌드하지 않는다.
- 앞선 수동 age 암호화 → PC 전송 → 별도 PostgreSQL 복원은 **20개 검사·약 83초**였다. 이번 S3 자동 백업의 3파일 계약·10개 검사·19.477초와 다른 실행 결과다.
- 두 복원 모두 전체 서비스 복구 시간이나 실제 회원 데이터 이전 완료를 뜻하지 않는다.

## 실제 계정 점검과 권고

현재 계정은 일반 Paid Plan이며 Basic 지원·기존 적격 크레딧을 유지한다. 적격 요금에 크레딧이 우선 적용되고 초과·만료·제외분은 청구될 수 있다. 2026-10월 Bills에서 Lightsail 사용료와 동일 금액의 크레딧 상계를 확인한 것은 이전 점검 기록이며 월말 확정 청구와 구분한다. 기존 예산은 Credit·Refund를 제외한 비용을 감시하고, 수신 이메일 인증 및 실제/예상 알림을 확인한 기록을 유지한다. 예산은 지출 차단 장치가 아니다.

이전 Free Plan 검토에서는 AWS 약관의 “Free Plan accounts are provided for evaluation purposes and should not be used for processing sensitive data.”를 확인했다. 모든 회원 이름을 같은 민감 데이터 분류로 단정하지 않는다. 당시 **일반 Paid Plan 전환 권고**, 사용자의 Free 유지 결정과 이후 2026-10-25 Paid 전환 완료를 구분한다. 후속 AWS Basic 계정·결제 재문의의 사람 답변은 별도로 검토하며 Paid 전환이 실제 데이터 이전·공개 승인이나 기술 준비 완료를 뜻하지 않는다.

일반 Paid 전환 후에도 남은 적격 크레딧을 대상 요금에 적용하되 초과·만료·적용 제외분은 청구될 수 있다. Paid에서 Free로 돌아갈 수 없다는 조건을 설명하고 승인받아 전환했다. 전환 화면의 조건만 확인하고 최종 버튼을 누르지 않았던 설명은 이전 단계의 이력이다. Organizations·Control Tower 가입이나 새 유료 Support 가입은 이번 전환에 포함하지 않았다.

Free Plan 당시의 최대 6개월/크레딧 소진 종료와 크레딧별 만료일은 다른 값이었다. 초기 콘솔 화면에서는 정확한 플랜 종료 timestamp를 확인하지 못했으나 후속 읽기 전용 `GetAccountPlanState`에서 당시 **FREE·ACTIVE와 정확한 expiry 필드**를 확인했다. 현재 Paid 상태와 혼동하지 않고 계정별 시각·잔액·크레딧별 만료일은 비공개로 보관한다. 공식 약관의 네 가지 질문과 설정 암호문 범위를 중립적으로 정리한 초안을 후속 Basic 지원 문의로 접수했고, 당시 미전송 설명과 현재 재문의 답변 대기를 구분한다.

근거: [Free Tier 약관](https://aws.amazon.com/free/terms/), [계정 플랜과 전환](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html), [Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/), [예산 주의사항](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-best-practices.html).

## 저장소 사전 비교 기록 — 이후 서울 S3 선택·검증

| 후보 | 공식 요금·특성 | 판단 |
|---|---|---|
| S3 Standard 서울 | 첫 50TB $0.025/GB·월, PUT/COPY/POST/LIST $0.0045/1,000회, GET 등 $0.00035/1,000회 | 국내 리전·세밀한 IAM·Versioning·Object Lock·Lifecycle의 우선안. 같은 AWS 계정 중단에 대비해 PC 복사본도 유지 |
| Cloudflare R2 Standard | 월 10GB-month·Class A 100만·Class B 1,000만 무료 범위, 초과 저장 $0.015/GB·월 등. 송신 요금 없음 | 다른 공급자에 보관하는 대안. 새 구독·초과 요금 동의 필요. APAC 위치 힌트가 국내 보관을 보장하지 않음. 무료 범위는 비용 상한이 아님 |
| Lightsail Object Storage | $1/월에 5GB 저장·25GB 송신, 초과 별도 | 정액 후보지만 일반 S3의 세밀한 권한·보존 잠금 API와 같지 않음. 과거 3개월 체험을 신규 크레딧에 중복 적용한다고 가정하지 않음 |

위 비교 후 사용자가 **서울 비공개 S3 Standard 버킷 1개 + 서버 age 암호화 + PC 독립 복원**을 승인하여 구성·합성 검증했다. R2나 Lightsail Object Storage는 새로 만들지 않았다. S3 백업 사용료의 실제 크레딧 상계는 아직 별도 확인이 필요하다. 동일 서울 리전이어도 Lightsail의 공개 경로 외향 전송은 기본 전송량을 소모할 수 있으므로 무조건 무료로 계산하지 않는다.

근거: [서울 S3 공식 요금표, 2026-10-15 발행](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/ap-northeast-2/index.json), [S3 요금](https://aws.amazon.com/s3/pricing/), [R2 요금](https://developers.cloudflare.com/r2/pricing/), [R2 위치](https://developers.cloudflare.com/r2/reference/data-location/), [Lightsail 요금](https://aws.amazon.com/lightsail/pricing/), [Lightsail Object Storage 지원 API](https://docs.aws.amazon.com/lightsail/latest/userguide/object-storage-s3-actions.html), [Lightsail 전송량](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-data-transfer-allowance.html).

## 승인된 주기·보존·권한과 남은 운영 절차

| 항목 | 정책·검증 범위 |
|---|---|
| 자동 백업 | 매시간 1회. 초기 축약 trigger와 별개로 실제 VM 재부팅 후 기존 PG 자동 복구·원래 hourly 발화·새 원격 검증 완료를 확인. 원래 restart 정책은 유지하고 source unit/백업 전처리로 healthy를 확인 |
| 보존 | 시간별 7일 + 주간별 28일. 이전의 시간 24개·일 7개·주 4개는 확정 정책이 아니며 이 안과 섞어 비용을 계산하지 않음 |
| 암호화 | 검증한 age 수신자 공개키만 서버에 둠. 복호화 개인키는 서버·버킷·Git에 넣지 않음. 저장소는 SSE-S3, 별도 KMS 자원은 생성하지 않음 |
| 삭제 방지 | Versioning + 기본 Governance Object Lock 7일. 서버 자격증명으로 삭제/우회 불가. 주간본이 28일 전체 잠금인 것은 아님 |
| 만료 정리 | 시간 경로 7일·주간 경로 28일의 Lifecycle과 noncurrent version·delete marker·중단된 multipart 정리. 처리 지연/버전/잠금으로 목표보다 더 보관될 수 있음 |
| 성공 기준 | dump·암호화·업로드 후 크기 및 다시 내려받은 암호문 checksum 일치까지 확인한 뒤 성공 시각 기록 |
| PC 복사·키 | PC 다운로드·독립 복원 완료. PC 밖 복구 개인키 직접 보관·사본 확인 완료는 2026-10-24 사용자 확인이며 에이전트의 외부 사본 직접 읽기 검증은 아님. 자동 PC 복사·정기 복원 담당/절차는 후속 |
| 목표 | RPO 1시간·전체 RTO 2시간을 제안. 정기 작업·경보·실데이터 복원 시험 전에는 달성으로 표시하지 않음 |

Object Lock을 켠 버킷은 기능을 끄거나 Versioning을 중지할 수 없다. Governance는 별도 우회 권한이 있는 관리자가 보존을 우회할 수 있어 계정 관리자 침해까지 막는 절대 불변 저장소가 아니다. 사용자 승인 후 Governance를 설정하고 합성 객체의 버전 삭제·보존 변경 거부와 복원을 확인했다. Compliance는 사용하지 않는다. 7일·28일이 실제 경과한 Lifecycle 만료를 기다려 검증한 결과는 아니다.

서버 전용 IAM은 지정 버킷/경로의 업로드·검증 읽기·제한된 목록 조회만 허용하고 삭제·버전 삭제·보존 우회·보존기간 변경·버킷/권한 관리를 허용하지 않는다. root API 키를 만들지 않는다. Lightsail VM에 EC2 인스턴스 프로필을 붙이는 것으로 설계하지 않으며, 최소 권한 전용 자격증명의 root 전용 파일 보관·회전·폐기 절차를 검증한다. 관리자와 복원 권한은 운영 서버에 두지 않는다.

현재 합성 자동 백업은 데이터·시퀀스·Flyway와 복원 manifest/릴리스 정보를 담은 3파일 계약이다. 서버 준비·합성 QA 설정 30파일과 추가 3파일의 수동 암호화·복구는 별도 결과다. 후속으로 새 DB 자격증명·VAPID의 5개 논리값을 포함한 **비활성 운영 설정 16파일**의 로컬 생성·암호화 복원을 완료했지만 서버에 설치하지 않았다. 소비자별 Linux 권한·런타임 파일 분리와 운영 DB용 정기 백업은 [적용 계획](PRODUCTION_INSTALL_PLAN.md)의 후속 작업이다. 실제 기술 자격증명을 포함한 archive를 순수 합성 데이터로 취급하지 않는다. 공개 manifest/로그에 비밀값을 넣지 않는다. 서버에는 age 공개 수신자만 두고 개인키를 전달하지 않았다. 서버 임시 평문을 최소화하고 작업 실패 시 이전 정상 원격 백업을 삭제하지 않는다.

근거: [S3 Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html), [Lock 관리](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock-managing.html), [Lifecycle 만료](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html), [정책 예시](https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-policies-s3.html), [Lightsail IAM 역할 제약](https://aws.amazon.com/blogs/compute/using-aws-codedeploy-and-aws-codepipeline-to-deploy-applications-to-amazon-lightsail/).

## 승인된 실패·중단 경보와 실제 시험 범위

사용자 승인 후 CloudWatch 지표 1개·표준 경보 1개·SNS Standard 이메일 구독 1개를 구성했다. 사용자가 구독을 확인하고 stale ALARM·복구 OK 메일 모두 실제 수신했다고 확인했다. 서버의 로그와 별도로 AWS에서 평가하며, 서버는 metric 발행만 하고 경보/SNS 관리나 직접 Publish를 하지 않는다.

- 성공 시각은 원격 암호문 검증이 끝나야 갱신한다. 별도 5분 감시 작업이 `VerifiedBackupAgeMinutes` 지표를 발행하고 마지막 검증 후 90분 초과를 경보 조건으로 삼는다. 미수신도 경보로 처리한다. 매시간 성공 때만 드물게 지표를 발행하는 방식과 구분한다.
- 정상·DB 정지로 인한 백업 실패/복구·격리된 빈 성공 상태의 stale ALARM/OK·양쪽 메일 수신을 검증했다. 추가 미수신 시험은 기존 경보의 데이터 없는 차원으로 약 50.35초 뒤 breaching ALARM을 확인하고 원래 설정을 복원한 뒤 최종 OK·SNS action 성공까지 확인했다. 실제 감시 프로세스/VM 중단과 90분 대기 시험은 하지 않았다. 과거 데이터 재사용·집계/메일 지연 때문에 정확한 90분 수신을 보장하지 않는다.
- 서버는 지정 namespace의 `cloudwatch:PutMetricData`만 추가로 허용한다. alarm·SNS 관리/직접 Publish 권한은 주지 않는다. 파일명/시간을 dimension에 넣어 지표 개수와 비용을 늘리지 않는다.
- SNS 게시자는 정확한 CloudWatch 경보·계정으로 제한한다. 상태 전환 통지이며 ALARM 유지 중 자동 반복 메일을 보장하지 않는다. AWS 계정 정지·알림 서비스 장애·탈취 서버의 지표 위조는 별도 한계다.

정가 예시: 지표 $0.30 + 경보 $0.10/월, API $0.01/1,000회. 5분마다 1회·31일이면 API 8,928회 약 $0.0893, 기본 합계 약 **$0.49/월**이다. 계정 공용 무료 지표/경보/API에 여유가 있으면 더 적거나 $0일 수 있으나 이번 점검에서 그 잔여량을 확정하지 않았다. SNS는 월 이메일 1,000건 무료 범위, 이후 $2/100,000건 등이다.

근거: [CloudWatch 요금](https://aws.amazon.com/cloudwatch/pricing/), [서울 공식 요금표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonCloudWatch/current/ap-northeast-2/index.json), [미수신 평가](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/alarms-and-missing-data.html), [namespace 제한](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/iam-cw-condition-keys-namespace.html), [SNS 요금](https://aws.amazon.com/sns/faqs/), [이메일 구독](https://docs.aws.amazon.com/sns/latest/dg/sns-email-notifications.html).

## 비용 계산과 제한

실제 운영 덤프 크기는 이번 점검에서 측정하지 않았다. 다음은 크레딧 차감 전 USD 가정 계산이다.

| 가정 | 정가 추정 |
|---|---:|
| 기존 Lightsail | $24/월 |
| 시간 168개 + 주간 4개, 각 암호문 100MB=0.1GB 환산 | 약 17.2GB, 저장 $0.43/월 |
| 같은 개수에 각 암호문 1GB | 약 172GB, 저장 $4.30/월 |
| 감시 1지표·1경보·5분 API, 무료 적용 전 | 약 $0.49/월 + SNS |

100MB 예시의 서버·백업·감시 기본액은 약 **$24.92/월 + 저장 요청·버전·전송·세금 등**이다. 보존 처리 지연·noncurrent version·검증 다운로드·multipart 요청은 별도다. 원격 checksum 검증 다운로드도 요청/전송량에 포함한다. **백업+감시 월 $2 이내, 전체 월 $26 이내를 초기 설계 목표로 제안**하되 비용 상한이나 청구 보장이 아니다. 실제 크기·버전 포함 용량·실제 크레딧 상계를 관측해 갱신한다. 큰 덤프·요청 급증·자격증명 악용은 이를 초과할 수 있다. 기존 월 $30 예산은 유지한다.

## 남은 작업과 실제 이전 승인 경계

1. 검증한 실제 재부팅·원래 hourly 백업·현재 부팅 metric/CloudWatch OK를 기준으로 장시간 운영을 관측한다. 90분 장애 수신 지연·전체 RPO/RTO는 별도 검증한다.
2. AWS Basic 계정·결제 재문의의 사람 답변과 Paid 전환 후 실제 사용료·적격 크레딧 적용·만료/제외분을 확인한다. 이전 Free Plan 이력을 현재 상태로 취급하지 않는다.
3. 기존 30파일 archive·추가 3파일 archive·새 비활성 설정 16파일 archive를 구분해 관리한다. 자동 PC 복사·정기 독립 복원 담당/절차와 소비자별 런타임 secret 권한을 준비한다. 기존 PC 밖 복구키 사본 확인은 사용자 확인이며 새 암호문까지 PC 밖에 보관한 결과는 아니다.
4. synthetic-only 실행기·source guard를 유지하고 별도 운영 DB 백업·최소 권한·독립 복원·감시를 후속 승인 범위에서 구현한다. 공개 전 성공을 확인하며 세부 순서는 [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md)을 따른다.
5. 실제 백업/감시 요금의 크레딧 상계·버전 포함 보관량을 확인한다. RPO 1시간·전체 RTO 2시간은 아직 목표이며 달성 결과가 아니다.
6. **새 최종 백업·쓰기 동결·실회원 데이터 이전·공개 전환**의 범위·원본 보존·중단 조건을 구체화한 뒤 별도 승인받는다. 새 쓰기 이후에는 최신 운영 DB를 보존한 복구 계획을 사용한다. 현재 개발 DB·관리자·명단/팀·예약·볼륨과 공개 준비 화면을 보존한다.

이 문서의 현재 완료 범위는 승인된 합성 백업·PG 복원·정기 작업 구성·제한된 실패/경보 시험이다. 실제 회원 서비스의 운영 준비 전체나 전체 복구 시간 달성을 주장하지 않는다.
