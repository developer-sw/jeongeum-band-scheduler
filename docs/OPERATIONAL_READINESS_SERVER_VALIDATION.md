# 백업 준비 대기 코드의 실제 운영 적용·독립 복원

후속: 이 적용 기록 뒤 승인된 [최신 설정 암호문 갱신·독립 복원·새 코드 재부팅](OPERATIONAL_SETTINGS_REFRESH_REBOOT.md)을 이어갔다. 아래 설정 복구본 갱신·재부팅 미실행은 당시의 범위이며 후속 실행 상태는 새 기록을 따른다.

검증일: **2026-10-27, Asia/Seoul**, 재개 후 관측 **2026-10-28**. 사용자 승인으로 [PR #33](https://github.com/developer-sw/jeongeum-band-scheduler/pull/33)의 main `351bf69` 백업 코드를 기존 운영 서버에 적용했다. 같은 main의 [CI 7개 job 성공](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/40501675129)을 확인한 뒤 진행했다. [준비 대기 계약과 격리 검증](OPERATIONAL_READINESS.md), [이전 운영 설치·백업 검증](OPERATIONAL_SERVER_VALIDATION.md)과 구분하는 새 실행 기록이다.

## 교체 범위와 보존

기존 백업·감시 lock을 획득한 동안 `operational-backup.py` **한 파일만 원자적으로 교체**했다. 이전 코드는 root 전용 위치에 보관해 코드만 되돌릴 수 있게 했다. 공통 모듈·설정·자격증명·성공 상태를 이전 값으로 복원하는 작업은 하지 않는다. 새 service 파일은 주석만 달라 실제 설치 파일을 유지했고, `daemon-reload`나 timer 중지는 수행하지 않았다.

| 검사 | 실제 결과 |
|---|---|
| 서버 자원 | 컨테이너 7개·볼륨 6개와 기존 이미지·mount·restart 상태 보존 |
| 두 원본 DB | 각 39테이블·29시퀀스·성공한 Flyway V1~V12, 전체 행·시퀀스·인증·권한 지문 보존 |
| 설치 설정 | 선택 34파일 중 실행자 1개만 변경. 나머지 33파일의 내용·권한과 기존 별도 33파일 보존 |
| 원본 바인딩 | rc.3 앱의 `source_sha`·이미지 pin·DB/볼륨 바인딩 유지. 앱 출처를 백업 도구의 main SHA로 바꾸지 않음 |
| 자동 실행 | 기존 백업·감시 timer 4개 모두 enabled/active 유지 |
| 앱·공개 상태 | 운영 Backend 중지·worker/Push 5개 flag OFF, 기존 HTTPS 준비 화면 유지 |

교체 도구의 로컬 fake filesystem 검사 **21개**와 별도 격리 Linux의 실제 POSIX 검사 **6개**를 통과했다. 실제 서버에서는 **23:34:13 KST**에 보존 감사를 다시 통과했다. 별도 HTTPS GET도 기본 TLS 인증서 검증을 사용해 200과 기존 준비 화면을 확인했다.

## 새 코드의 실제 백업·감시

설치된 새 코드의 `validate`·`ready` 진단을 통과했으며 `ready`가 PostgreSQL을 시작하지 않았음을 확인했다. 이어 실제 systemd backup service의 단발 실행으로 **23:20:56 KST** snapshot을 만들고 **23:20:59 KST** 새 hourly 객체의 정확한 VersionId·크기/SHA 원격 검증과 성공 상태 갱신을 마쳤다. UTC 시각은 각각 `14:20:56Z`·`14:20:59Z`다. 같은 주의 기존 weekly 객체는 재사용·보존했다.

실제 metric service도 새 성공 상태에서 신선한 지표를 발행했고 `stale:false` 및 성공 상태 불변을 확인했다. CloudWatch 데이터테이블의 **Maximum·300초 bucket**에서 **23:20 KST 0.07분**, **23:25 KST 5.24분**을 관측해 새 snapshot 나이의 반영을 확인했다. 이는 bucket 시각이며 정확한 발행 초를 뜻하지 않는다. 기존 경보는 OK였다.

위 초기 실행은 교체 후의 수동 단발 systemd 검증이다. 이후 자연 hourly 관측은 아래 재개 후 결과와 구분한다. 재부팅·누락 회차 catch-up이나 실제 서버의 강제 준비 timeout 시험은 수행하지 않았다. 지연·timeout·중복 조건의 격리 검증은 [이전 합성 결과](OPERATIONAL_READINESS.md)를 따른다.

## 2026-10-28 재개 후 자동 실행 관측

**09:38:16 KST** 읽기 전용 보존 감사에서도 컨테이너 7개·볼륨 6개·두 DB·설정·4개 timer·같은 부팅 상태를 확인했다. **09:38:37 KST** HTTPS GET도 TLS 검증·200·기존 준비 화면을 통과했다.

**09:40:20 KST** 설치 코드 hash·보호된 성공 상태·systemd journal·timer의 마지막 trigger를 읽었다. 별도 백업이나 metric을 수동 실행하지 않았다. **00시~09시 각 정각 회차 10개**의 서로 다른 backup invocation에서 `verified` 성공을 확인했다. 최근 timer trigger는 **09:00:27**, snapshot 시작은 **09:00:28**, 원격 검증 완료는 **09:00:31 KST**로 연결된다. 최근 metric은 **09:37:22 KST**에 `stale:false`·성공 상태 변경 없음으로 발행됐다. CloudWatch 콘솔을 새로 고쳐 기존 경보의 OK도 다시 확인했다. 따라서 새 코드의 정규 hourly 실행도 관측했으나, 새 코드가 설치된 상태의 실제 재부팅·누락 회차 catch-up 검증은 남아 있다.

## 서버 밖 새 백업의 독립 복원

이번 새 hourly 암호문 **21,600bytes**를 기존 서울 S3의 전용 읽기 권한으로 PC에 내려받았다. 정확한 VersionId·ExpectedBucketOwner·TLS·AES256·메타 SHA와 다운로드 SHA를 대조한 뒤 Windows age로 인증했다.

기존 DB나 보존된 복원 target을 사용하지 않고, PC의 **새 PostgreSQL 17.11 엔진·새 빈 DB·새 볼륨**에 복원했다. 대상은 network none·호스트 포트 없음이며 다음을 확인했다.

- 39테이블·29시퀀스·성공한 Flyway V1~V12
- 원본과 테이블 행·시퀀스·Flyway 지문 일치
- 최소 권한 역할·DB/테이블 소유권 일치
- 기존 Docker 메타데이터 보존과 새 검증 target 보존

복구 개인키는 PC의 Windows age만 읽었고 서버나 Linux 컨테이너로 전달하지 않았다. 평문 archive는 디스크에 저장하지 않고 인증된 메모리의 dump만 복원 프로세스에 전달했다. **Windows age 메모리 bridge 검증**이며 원본 Linux root 복원 CLI 전체 실행과 구분한다.

## 이번 실행의 한계와 다음 단계

실회원 없는 기존 운영 원본의 코드 적용·백업·복원 검증이다. 실제 개발 DB 접속·실회원 데이터 이전·앱 공개 전환·시간 조작·강제 실패·서버 재부팅·새 AWS 리소스나 IAM 권한 변경은 수행하지 않았다. 기존 원본·볼륨·키·암호문을 유지했으며 이번 적용 기록의 추가 커밋·Push는 하지 않았다.

이전 선택 34파일의 암호화 설정 복구본은 **이전 백업 코드**를 담고 있다. 이번 DB 암호문의 독립 복원 성공을 최신 설정 복구본 갱신으로 대신하지 않는다. 새 코드가 포함된 암호화 복구본 갱신·독립 복원·PC 밖 사본 확인과 새 구성의 재부팅 검증은 다음 단계다. 전체 서비스 RPO/RTO·부하 용량·실제 회원 이전과 공개 전환 완료를 주장하지 않는다.

실제 계정·버킷·객체 식별자·IP·키·개별 비밀파일 지문·상세 실행 기록은 공개 문서에 포함하지 않는다.
