# 운영 준비 설정의 암호화 백업·복구 검증

후속 준비(2026-10-24): 별도의 **새 미사용 운영 설정 16파일**을 로컬에 생성하고 신규 `inactive-production-settings` 범위로 실제 age 암호화·Windows 독립 복원을 완료했다. 기존 범위의 기본 동작과 v1 계약은 유지하며, 신규 범위만 명시적 opt-in·v2/생성 true/상태 staged-inactive를 요구한다. [신규 결과](INACTIVE_PRODUCTION_SETTINGS.md)를 따르고 아래 초기 시험의 새 운영 비밀값 미생성 설명과 구분한다. 서버 설치·실제 데이터 이전·활성화는 하지 않았다.

게시 전 보완(2026-10-24): Windows 예약 장치명에 콘솔 별칭과 위 첨자 COM/LPT를 추가하여 Linux에서 만든 archive도 Windows 복원 폴더 생성·쓰기 전에 거부한다. 새 fake archive 회귀를 포함한 27개 중 로컬 26개 통과·hardlink 1개 건너뜀과 새 Linux CI 범위는 [TEST_PLAN](TEST_PLAN.md)을 따른다. 아래 실제 30파일·추가 3파일 복원은 당시 정상 allowlist를 사용한 결과이며, 소스 보완을 원격 서버에 재설치한 검증은 아니다.

후속 상태(2026-10-24): AWS Basic 계정·결제 문의 접수와 실제 VM 재부팅·기존 합성 PG/준비 Caddy 자동 복구, 부팅 metric·CloudWatch 새 지표와 최종 OK를 확인했다. 재부팅 전후 **아래 원본 30파일의 SHA는 동일**하다. 새 readiness helper·source unit·백업 drop-in **3파일은 별도 추가 자산이며 기존 암호문에 들어 있지 않다**. 별도 age v1.3.2 암호문 **4,356bytes**의 원본 불변·서버/PC 전체 SHA 일치·실제 Windows 인증 복원·3파일 disk SHA와 현재 사용자/SYSTEM native ACL을 확인했다. **기존 30파일 archive + 별도 추가 3파일 archive를 함께 쓰는 수동 복구**이며 단일 33파일 archive·자동 활성화·S3 설정 업로드·helper 자체 Linux restore는 아니다. 현재 결과와 점검 절차는 [OPERATIONS_REBOOT_VALIDATION](OPERATIONS_REBOOT_VALIDATION.md)을 따른다. 아래 초기 설정 시험의 신규 자산 미설치·문의 미전송 설명은 당시 범위다.

검증일: **2026-10-24, Asia/Seoul**. 기존 미커밋 작업과 원본 서버 설정을 보존하고, 명시적으로 허용한 **운영 준비·합성 QA 설정 30개 파일**을 암호화하여 PC의 새 비공개 폴더에 복원했다. 실회원 데이터 이전·운영 앱 공개·설정 활성화는 하지 않았다.

DB는 합성이지만 설정에 포함된 기존 서버의 최소 IAM 자격증명과 DB 비밀번호는 **실제 기술 비밀값**이다. 이를 합성 계정 fixture로 취급하거나 공개하지 않는다. 실서비스용 새 DB 자격증명·VAPID 키는 아직 생성하지 않았다.

## DB 자동 백업과의 구분

| 항목 | 기존 정기 DB 백업 | 이번 설정 백업 |
|---|---|---|
| 도구 | `scripts/production-backup.py` | `scripts/configuration-backup.py` |
| 내용 | DB dump·DB manifest·백업 manifest 3파일 | 명시적 allowlist의 설정 30파일·암호화된 manifest/source metadata |
| 실행·저장 | 서버 타이머 → 승인된 S3 경로 | 수동 1회 암호화 → 브라우저 SSH로 암호문만 PC 전송 |
| 실제 복원 | 별도 PC PG 엔진·볼륨, 10개 검사 | 새 Windows 비공개 폴더, 30파일 대조·통합 21개 검사 |
| 자동화 범위 | 합성 DB의 정기 백업 | 자동 설정 수집·정기 설정 백업·자동 활성화 없음 |

이번 설정 암호문은 **64,967bytes**이며 서버/PC SHA 일치를 확인했다. 서버 밖 사본은 PC에 보관하고 원격 암호문 원본도 유지했다. S3 새 업로드·공개 다운로드 URL·새 포트·새 AWS 자원은 없다.

## 허용한 설정과 제외 항목

| 허용 그룹 | 범위 |
|---|---|
| 공개 준비 화면 | Caddy 준비 설정과 정적 자산 |
| 기존 합성 QA | Compose·실제 QA Caddyfile·`qa.env`, QA DB·bootstrap 자격증명과 비어 있는 VAPID 3파일 |
| 백업 실행 환경 | 백업 config·최소 IAM 자격증명·실행기·systemd unit 4개 |

디렉터리 탐색이나 wildcard로 수집하지 않고 검토한 파일만 지정한다. 계정·주소·수신 이메일·자격증명 값·파일별 비밀값 SHA는 공개 문서에 넣지 않는다. 파일별 원본 경로·SHA·크기·mode/uid/gid와 출처 정보는 암호문 내부 manifest와 Git 제외 증빙에서만 관리한다.

**TLS 개인키·age 복구 개인키·SSH 개인키·root API 키는 archive에서 제외**했다. HTTPS 복구는 도메인 제어권을 확인한 뒤 인증서 재발급을 별도로 진행한다. 이 설정 복원은 인증서·VM·전체 서비스 복구를 완료한 결과가 아니다.

## 실행기의 방어 경계

- 최대 128개 파일·평문 총 32MiB이며 기본 범위는 `server-preparation-and-synthetic`이다. 신규 비활성 운영 설정은 pack·restore 양쪽에 별도 scope를 명시해야 하며 기본 호출에서 수락하지 않는다.
- age **v1.3.2**와 공개 수신자로 암호화한다. 복구 개인키는 서버에 전송하지 않고 PC의 보호된 파일에서 age만 읽는다.
- 원본 파일의 식별자·크기·mode·mtime·내용 SHA를 암호화 전후에 재확인한다. Windows의 lstat/fstat ctime 캐시 차이는 별도로 다루며 다른 동일성 검사는 유지한다.
- symlink·hardlink·특수 파일·경로 탈출·중복·Windows 장치명/ADS·키 파일 표식을 거부한다. 기존 출력 파일이나 복원 폴더를 덮어쓰지 않는다.
- 복호화 인증 종료 0과 gzip/tar 전체 경계·크기·SHA 검사가 끝나야 새 폴더에 쓴다. 오류는 고정 코드만 출력하며 비밀값·CLI stderr·평문 본문을 출력하지 않는다.

도구의 `pack`은 private allowlist, 공개 recipient, age 실행 파일과 새 output 경로를 받는다. `restore`는 암호문·private identity·age·새 destination 경로를 받는다. 실제 allowlist·키·명령 인자 값은 비공개로 보관하고 복원 파일을 실행 중 경로로 자동 복사하지 않는다.

## 실제 검증 결과

| 검증 | 완료 결과 |
|---|---|
| 오프라인 | fake age 기반 **26개 검사 통과**, 별도 읽기 전용 검토에서 차단 결함 없음 |
| 실제 암호화·전송 | 서버 age 암호화 후 암호문만 브라우저 SSH로 전송, PC에서 크기·SHA 일치 |
| Windows 실제 복원 | 새 private 폴더의 **30개 파일 전부 disk SHA·크기 일치**, native ACL을 현재 사용자·SYSTEM으로 확인 |
| 비공개 통합 | **21개 검사 통과**: 참조 파일 map·QA 역할·두 DB 비밀번호의 구분·worker/Push OFF·빈 VAPID·출처 구분 |
| 실제 실패 거부 | 변조·절단·유효한 다른 키·기존 destination 거부, 부분 복원 폴더 0개·비밀값 stdout 0 |
| Linux metadata 재현 | 기존 PostgreSQL 17 이미지의 파일 도구만 사용한 격리 컨테이너에서 30개 파일 SHA·mode·uid/gid 일치 |
| 보존 | 원본 수정·설정 활성화·실회원 DB 접근 없음. 기존 컨테이너 ID·볼륨명 보존 |

Linux 검증 컨테이너는 `network=none`·tmpfs·read-only·`entrypoint=sh`였고, 실제 DB 시작·호스트 mount·DB volume을 사용하지 않았다. 시험 후 해당 일회 컨테이너는 자동 제거했다. 이는 **Windows 실제 복원 + Linux metadata 재현**이며 helper 자체의 Linux restore 경로를 실행한 시험이 아니다. Windows에서는 native ACL을 별도로 검증하며 POSIX mode/uid/gid와 동일한 권한 모델이라고 주장하지 않는다. 검증 후 임시 평문 설정 사본·manifest와 일회용 다른 키는 정리하고 암호문·검증 기록을 보존했다.

## 활성화 전에 다시 확인할 참조

현재 QA `qa.env`는 과거 복원 시험의 대상 DB를 가리키며 기존 정기 DB 백업의 원본 DB와 다른 참조를 그대로 보존했다. 이 차이를 조용히 수정하거나 같은 DB로 보고하지 않는다. 향후 활성화 전 **복원할 DB·앱 연결 대상·역할·두 비밀번호 파일의 대응**을 다시 검토해야 한다.

QA 출처는 **rc.2 / `8b24285`**, 향후 새 앱 배포에 사용할 검증 릴리스는 **rc.3 / `a00a791`**이다. 준비 설정 보존이 새 운영용 비밀값 생성·rc.3 앱 전환을 대신하지 않는다.

## 계정·문의·남은 작업

후속 읽기 전용 `GetAccountPlanState`에서 **FREE·ACTIVE와 정확한 expiry 필드**를 확인했다. 초기 콘솔의 timestamp 미확인 기록은 당시 한계이며 현재 미확인 항목으로 되풀이하지 않는다. 계정별 정확한 시각·잔액은 비공개로 보관하고 크레딧별 만료일과 플랜 종료를 구분한다. Paid 전환은 하지 않았다.

공식 약관에 관한 네 가지 확인 질문을 중립적으로 정리하고 실제 기술 자격증명을 담은 설정 암호문의 범위도 지원 문의 초안에 반영했다. 이 설정 시험 당시에는 미전송이었으며, **후속 AWS Basic 계정·결제 문의 접수까지 완료했고 답변 검토는 남아 있다**. Free Plan의 실회원 운영 적합성이 확정됐다고 표현하지 않는다.

**2026-10-24 사용자가 PC 밖 복구 개인키 직접 보관·사본 확인 완료를 알렸다.** 이는 사용자 확인이며 에이전트가 외부 사본을 직접 읽은 검증은 아니다. 원래 키에서 공개 recipient를 유도한 검사와 당시 통합 시험 기록은 해당 시점의 증거로 유지한다. 자동 설정 백업·S3 설정 암호문 보관·자동 PC 복사·새 운영 비밀값 생성/복구·전체 RPO/RTO·실회원 이전/공개는 완료하지 않았다.

전체 정책·승인 경계는 [OPERATIONS_BACKUP_PLAN](OPERATIONS_BACKUP_PLAN.md), 검증 분류는 [TEST_PLAN](TEST_PLAN.md), 기존 DB 실행 자산은 [deploy/backup](../deploy/backup/README.md)을 따른다.
