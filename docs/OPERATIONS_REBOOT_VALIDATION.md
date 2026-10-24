# 운영 준비 서버 재부팅·합성 백업 복구 검증

검증일: **2026-10-24, Asia/Seoul**. 기존 Lightsail VM을 실제 재부팅하고 공개 준비 화면과 기존 합성 PostgreSQL의 자동 복구를 확인했다. 실제 회원 데이터 이전·예약 앱 공개·유료 플랜/Support 전환은 하지 않았다.

## AWS 문의 접수

기존 네 가지 질문의 초안을 AWS **Basic 계정·결제 지원의 Free Tier 일반 문의**로 접수했다. Free Plan의 실제 회원 서비스 적용 범위, 플랜 종료와 크레딧 만료의 구분, 서비스별 크레딧 적용, 비용 화면의 집계 차이를 질문했다. [AWS Support](https://support.console.aws.amazon.com/support/home)에서 답변을 확인한다. 문의 접수와 조건 확정은 다르며 **답변 검토는 남아 있다**. 문의 번호·계정 정보·비공개 증빙은 Git 제외 기록에만 보관한다.

## 기존 합성 DB만 자동 시작하는 보완

`scripts/backup-source-readiness.py`와 `deploy/backup/jeongeum-backup-source.service`, 백업 서비스의 `source.conf` drop-in을 추가했다. 기존 컨테이너의 **정확한 전체 ID·프로젝트/서비스 label·이미지 pin·단일 지정 데이터 볼륨·포트 미공개**를 확인한 뒤 해당 합성 PG가 정지 상태일 때만 한 번 시작한다. 전체 제한은 120초이며 Docker/최종 읽기 전용 SQL 호출은 각각 최대 10초다. 알 수 없는 상태·dead/paused/restarting/removing·대상 불일치는 거부한다.

source unit은 Docker 뒤에 기동하고 healthy·DB 바인딩 검사를 완료해야 백업을 허용한다. `RemainAfterExit=true`이며 매 백업의 `ExecStartPre`도 같은 준비 상태를 다시 확인한다. sibling 백업 모듈은 명시한 경로의 root 소유권·상위 경로·쓰기 권한·링크를 검증한 뒤 불러온다. 성공 JSON 또는 고정 오류 코드만 출력하고 설정값·SQL·CLI 출력·traceback을 출력하지 않는다.

**QA Compose와 원본 합성 PG의 `restart: no`를 유지하며 컨테이너 5개 각각의 기존 정책이 불변**임을 확인했다. 공개 준비 Caddy는 자체 기존 정책을 유지한다. 새 컨테이너/볼륨 생성, 이미지 변경·재빌드, Compose 전체 기동은 없다. QA backend/frontend/Caddy와 worker는 시작하지 않는다. 원격 `systemd-analyze verify`, source unit의 enabled/active 및 부팅 journal의 `existingSourceStarted=true`를 확인했다. 별도 fake Docker 회귀 **21개 통과**는 실제 VM 검증과 구분한다.

## 실제 재부팅 결과

| 검증 | 실제 결과 |
|---|---|
| VM 재부팅 | 이전/이후 boot ID가 달라 실제 새 부팅을 확인 |
| 기존 자원 | 컨테이너 5개의 ID·이미지·mount·restart 정책과 전체 볼륨명 동일 |
| 자동 복구 | 기존 합성 PG와 공개 준비 Caddy healthy, QA 앱 컨테이너 3개는 중지 유지 |
| 설정 보존 | 앞서 암호화한 원본 설정 30파일 SHA 동일. 새 readiness 자산 3개는 별도 추가 |
| 합성 DB 보존 | 39개 테이블 전체 행 지문·29개 시퀀스·Flyway V1~V12 전체 이력 동일 |
| 인증·권한 보존 | 역할·pg_authid·DB/schema/table/sequence 권한의 비공개 지문 동일 |
| 부팅 후 대조 | 비공개 재부팅 후 검사 **16항목 통과** |
| 서비스·타이머 | source unit enabled/active, 기존 두 타이머 enabled/active. 현재 부팅의 metric journal은 `metric-published`·`stale:false`, 서비스 `Result=success`·종료 0 |
| 실제 hourly 발화 | **16:00:18 KST**(07:00:18 UTC)에 원래 `OnCalendar=hourly` 타이머 실행 |
| 원격 백업 검증 | **16:00:24 KST**(07:00:24 UTC)에 새 시간별 S3 객체의 정확한 VersionId·Head/Get·크기/SHA 검증 완료. 기존 같은 주 주간본 유지 |
| 외부 감시 | **16:01:00 KST**(07:01:00 UTC)의 새 CloudWatch datapoint·백업 나이 약 **1.37분**, 원래 경보 설정 보존과 최종 **OK** 확인 |
| 공개 HTTPS | 기본 인증서 검증을 사용한 GET **200**과 공개 준비 화면 문구 확인 |
| 계정 플랜 | 후속 읽기 전용 API에서 **FREE·ACTIVE** 확인 |

부팅 후 백업을 수동 실행하거나 축약 trigger를 넣지 않았다. 이 hourly 실행은 이전의 runtime 10초 trigger 시험과 다른 실제 관측이다. 데이터·권한의 원본 값과 지문은 공개 문서에 넣지 않는다. 실제 개발 DB에는 접속하지 않았다.

## 새 설정 3파일의 추가 암호화 복구

기존 30파일 archive를 보존한 채 readiness helper·source unit·drop-in **3파일만 별도 age v1.3.2 archive**로 백업했다. 추가 암호문은 **4,356bytes**이고 원본 불변·서버/PC 전체 암호문 SHA 일치·실제 Windows age의 복호화 인증 성공을 확인했다. 복원된 3파일의 disk SHA가 저장소 파일과 일치하고 native ACL은 현재 사용자·SYSTEM으로만 제한된다.

이는 **원래 30파일 archive + 추가 3파일 archive를 함께 사용하는 수동 복구**다. 33파일을 하나의 archive로 다시 만들거나 자동 활성화하지 않았다. 서버 복구 개인키 전송·S3 설정 암호문 업로드·helper 자체 Linux restore 실행은 하지 않았다. 원래 암호문과 별도 추가 암호문의 역할을 구분해 보관한다.

## 점검 중 자동 시작을 중단할 때

이 보완 후에는 **수동으로 멈춘 해당 합성 PG도 다음 백업 전처리에서 다시 시작될 수 있다**. DB를 정지한 채 유지해야 하는 점검은 다음 순서로 진행한다.

1. `jeongeum-backup.timer`를 중지하고 진행 중인 `jeongeum-backup.service`가 끝났는지 확인한다. 진행 중인 백업을 무작정 강제 종료하지 않는다.
2. `jeongeum-backup-source.service`를 중지한 뒤, 확인한 정확한 기존 합성 PG만 정상 정지한다. source unit에는 DB를 자동 정지시키는 `ExecStop`이 없다.
3. 점검 중에는 백업 서비스를 수동 시작하지 않는다. 감시를 유지하면 장시간 백업 중단은 stale 경보 대상이 된다.
4. 복귀할 때 source unit을 시작해 healthy를 확인하고 백업 타이머를 다시 활성화한다. 다음 실행·원격 검증 완료·감시 상태를 확인한다.

## 아직 완료하지 않은 범위

- AWS 지원 답변 검토는 남아 있다. 문의 접수와 Free Plan의 실회원 운영 적합성 확정은 구분한다.
- 장시간 VM 장애·90분 경과 후 이메일 수신 지연, 자동 PC 복사, 실제 비용의 크레딧 상계와 전체 RPO/RTO 달성은 검증하지 않았다.
- 실제 데이터 이전·예약 앱 공개는 별도 승인 단계다. 새 운영용 비밀값 생성/복구와 전체 서비스 복구는 이 합성 DB·준비 화면 시험에 포함하지 않는다.

기존 [정기 백업 정책](OPERATIONS_BACKUP_PLAN.md), [설정 복구 결과](OPERATIONS_CONFIGURATION_RECOVERY.md), [실행 자산](../deploy/backup/README.md)을 함께 따른다. 이전 문서의 문의 미전송·재부팅/실제 hourly 미검증 설명은 해당 선행 단계의 기록이다.
