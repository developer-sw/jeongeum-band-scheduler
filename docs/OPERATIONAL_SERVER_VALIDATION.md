# 운영 서버 적용·실제 백업 검증

검증일: 2026-10-26~27, Asia/Seoul. 기존 Lightsail 서버와 `v1.0.0-rc.3` 고정 이미지를 재사용했다. 앱 소스는 `a00a791`, 백업 도구는 PR #31의 main `79811a0` 기준이다. 이번 단계에서 앱 이미지를 재빌드하거나 실제 개발 회원 데이터를 이전하지 않았다.

## 적용한 범위

| 항목 | 실제 결과 |
|---|---|
| 설정·권한 | 보호된 새 경로에 원본 5개 논리값·소비자별 runtime 6파일을 설치했다. master는 root `0600`, runtime은 해당 소비자 `0400`이며 PG init의 공개 스크립트만 root `0444`다. 설치 25파일의 내용·소유권·모드를 대조했다. |
| 새 운영 DB | 새 전용 PostgreSQL·볼륨·비공개 네트워크를 생성했다. rc.3 Backend를 production profile로 실행해 실제 Flyway V1~V12와 정상 건강 상태를 확인한 뒤 Backend를 중지했다. 39테이블·29시퀀스와 최소 권한 앱 역할 소유권을 확인했다. |
| 데이터·공개 경계 | 회원·가입 전 명단·팀·예약·알림 등 주요 업무 데이터는 0건이다. migration의 시간 블록·악기 등 초기 기준 데이터는 존재한다. worker·Push 5개 flag는 OFF이며 운영 Frontend·Caddy는 만들지 않았다. 공개 HTTPS는 기존 준비 화면을 유지한다. |
| 기존 환경 | 원래 서버 컨테이너·볼륨 각 5개, 합성 백업 source/timer, 준비 edge와 설치 25파일을 전후 대조했다. 개발 DB·관리자·회원·팀·예약·기존 볼륨에 접근하거나 이를 초기화하지 않았다. 원본 16/25파일과 이전 암호문도 유지한다. |

실제 설정의 서브넷과 초기 점검 후보가 다른 것을 발견해 **설정의 실제 서브넷으로 호스트 route·Docker 네트워크 충돌 검사를 다시 수행**했다. 전체 Compose를 한 번에 시작하지 않고 새 PG와 Backend만 순서대로 기동했다. 최초 설치 검증기의 Docker `IPAM.Config=null` 처리를 보완한 뒤 기존 파일을 재작성하지 않는 별도 검증으로 25파일·권한·이미지를 확인했다.

## 실제 S3 백업과 서버 밖 독립 복원

기존 서울 비공개 버킷의 Versioning·SSE-S3·공개 차단·Governance 7일과 합성 정책을 유지했다. 사용자 권한·비용 승인 후 운영 전용 IAM 사용자·키, 운영 prefix의 시간별 7일·주간별 28일 보존, 운영 지표·경보 각 1개와 기존 SNS의 정확한 새 경보 허용 조건을 추가했다. 운영 IAM은 해당 객체의 Put/Get/GetVersion과 전용 namespace의 PutMetricData만 허용하며 다른 정책·그룹을 부여하지 않았다. 새 버킷·VM·DB 서비스·SNS topic·구독은 생성하지 않았다.

**10월 26일 17:49:11 KST** snapshot의 age 암호문을 시간별·주간별 객체로 저장하고 **17:49:18 KST** 원격 검증을 마쳤다. 업로드 응답의 정확한 VersionId로 Head/Get하여 21,600bytes·SHA·암호화를 대조했다. 같은 버전을 PC의 신규 최소 권한 키로 내려받아 TLS·버전·메타 SHA·스트림/디스크 SHA를 다시 확인했다.

Windows age로 인증한 뒤 archive 경계·원본 바인딩·파일 SHA를 검사하고, **서버 밖 PC의 새 PostgreSQL 엔진·새 빈 DB·새 볼륨**에 복원했다. 대상은 network none·호스트 포트 없음이며 원본 운영 DB나 기존 개발 DB를 복원 대상으로 사용하지 않았다. 다음 항목을 통과했다.

- 39테이블·29시퀀스·성공한 Flyway V1~V12
- 테이블 행·시퀀스·Flyway 지문 일치
- 최소 권한 복원 역할·DB/테이블 소유권
- 인증·원본 검증 후에만 새 복원 대상에 접근

복구 개인키는 PC의 Windows age만 읽었고 서버·Linux 컨테이너로 전달하지 않았다. 평문 DB archive를 디스크에 쓰지 않고 dump만 stdin으로 전달했다. 원본 복원기의 archive/source/target/fingerprint 검증과 PostgreSQL 복원 동작을 재사용한 **Windows 인증·의존성 bridge**이며, 원본 Linux root CLI 전체 실행과 구분한다. 검증한 새 PC 복원 target은 보존한다.

Windows PowerShell 5.1의 모듈 autoload 충돌과 Python 3.13의 `mkdir(0700)` ACL 차이는 child의 표준 모듈 경로와 보호된 parent ACL 상속으로 해결했다. ACL의 사용자/SYSTEM 제한은 유지했다. 관련 비공개 오프라인 회귀 **35개(17+18)**도 다시 통과했다. 기존 Backend·Frontend 전체 회귀를 이번 단계에서 재실행한 결과는 아니다.

## systemd·감시·메일

**10월 27일 01:45:39 KST** 실제 systemd backup service가 만든 새 snapshot은 **01:45:43 KST** 원격 검증을 마쳤다. backup·metric service 모두 `Result=success`, exit 0을 확인했다. 이는 수동 단발 service 실행이며 자연 hourly 발화로 표현하지 않는다.

복원·새 단발 백업·이메일 수신 조건을 확인한 뒤 **01:48:18 KST** 운영 백업·감시 timer 두 개만 enable/start했다. 원래 `OnCalendar=hourly` 및 5분 감시 설정을 바꾸지 않았고, 기존 합성 unit은 유지했다. 두 timer의 enabled/active와 등록 직후 감시 호출을 확인했다.

**02:00:05 KST** 원래 hourly timer의 자연 발화와 service 시작을 관측했다. **02:00:06 KST** 새 snapshot을 만들고 **02:00:12 KST** exact-version S3 검증·성공 상태 갱신을 마쳤으며 service는 exit 0으로 종료했다. 수동 실행·축약 trigger·주기 변경이나 Persistent catch-up으로 대신하지 않았다. 다음 hourly 예약과 두 timer의 enabled/active를 확인했다. 감시도 **01:58:35~37 KST** 자동 호출·exit 0과 **02:03:45 KST** 추가 자동 호출·다음 5분 예약을 확인해 등록 직후 한 번의 실행과 구분했다. 자동 생성된 최신 객체는 별도 AWS 읽기 전용 Head 검사에서 암호화·Governance 잠금·정확한 버전을 재확인하고 서버 성공 기록과 대조했다.

운영 지표는 성공한 snapshot의 시작 시각부터 나이를 계산한다. 최신 AWS 읽기 전용 검사에서 경보 OK·새 지표, 기존 보존/공개 차단·운영 IAM 범위·정확한 SNS 경보 조건을 확인했다. CloudWatch ALARM/OK action 성공과 별도로 **사용자가 두 시험 이메일 모두 실제 수신했다고 확인**했다. 작업 중단으로 timer가 꺼져 있던 기간의 오래된 성공 기록을 새 성공으로 취급하지 않고 재접속 후 새 백업을 실행했다.

## 새 설정 34파일 복구본

원본 25파일에 운영 백업 config·자격증명·unit 4개·실행자 2개·공식 Linux age를 더한 **선택 34파일**을 별도 `operational-installed-settings-v1` 복구본으로 구성했다. manifest·수동 복구 지침을 포함한 archive는 36 members다. 기존 16/25파일 archive는 덮어쓰지 않았다.

이는 **검토한 PC 원본으로 재구성한 복구본**이며 서버 전체 capture가 아니다. 현재 서버의 동일 34파일에서 내용 SHA·크기·uid/gid/mode를 모은 canonical aggregate와 대조해 전체 일치를 확인했다. 개별 비밀값이나 비밀파일 SHA는 출력하지 않았다.

새 age 암호문은 PC에 보관했다. 별도 Windows age 프로세스로 저장된 암호문을 인증하고 새 보호 폴더에 34파일을 실제 복원해 디스크 내용·SHA·native ACL을 검증했다. 성공 후 검증용 평문 34개·임시 자격증명·복원 폴더를 정리했으며 남은 임시 평문은 0개다. 복구 개인키·전송용 임시 identity는 archive에 포함하지 않았다.

Linux uid/gid/mode는 archive 기록과 실제 서버 대조로 검증했다. Windows 디스크 복원을 Linux 소유권 재현으로 표현하지 않는다. OS·Docker·AWS CLI 설치, TLS/IAM 정책 복구, 새 PG ID의 승인된 재바인딩과 앱/worker/timer 재활성화는 수동 복구 지침의 별도 절차다. 새 설정 암호문의 PC 밖 추가 사본은 아직 사용자 확인을 받지 않았다.

## 현재 한계와 다음 경계

이번 결과는 실회원 없는 새 운영 원본의 백업·복원 검증이다. 실제 개발 데이터의 최종 백업·쓰기 동결·실회원 복원·최종 대조·운영 origin 로그인/예약/Push·예약 앱 공개 전환은 후속 승인과 검증이 필요하다. 새 구성 적용 후 VM 재부팅 검증은 이번 단계에서 하지 않았다. 전체 서비스 재난 복구·부하 용량·전체 RPO/RTO 달성을 주장하지 않는다.

실행 증빙·실제 설정·계정/객체 식별자·암호문은 Git 제외 비공개 위치에 보관한다. 위 서버 적용·검증 단계 자체에는 GitHub 게시나 새 이미지 발행을 포함하지 않았다. 후속 사용자 승인으로 검증 문서·README를 커밋·Push하고 main 대상 초안 PR과 동일 HEAD의 원격 CI를 확인한다. 정식 PR 전환·main 병합은 별도 승인 대상이며 이미지 발행·실데이터 이전·공개 전환은 이번 게시 범위에 포함하지 않는다.
