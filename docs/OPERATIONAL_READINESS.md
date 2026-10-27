# 운영 백업의 PostgreSQL 준비 대기와 제한시간

기록일: **2026-10-27, Asia/Seoul**. [새 운영 구성 재부팅](OPERATIONAL_REBOOT_VALIDATION.md)에서 남긴 PostgreSQL 기동 순서의 보호 범위를 보완했다. 승인 범위인 준비 대기·제한시간의 로컬 구현과 합성 PostgreSQL 기동 지연·실제 systemd catch-up 검증을 완료했다. 실제 운영 서버는 기존 실행자를 유지하며 이번 새 guard를 적용하지 않았다. 로컬 검증에서는 AWS·운영 서버·실제 개발 DB·실제 키/설정/복구 암호문·GitHub 게시를 변경하지 않았으며, 후속 게시 승인은 아래 남은 단계와 구분한다.

## 준비 대기 계약

[운영 백업 실행자](../scripts/operational-backup.py)의 `backup` 경로 안에서 다음 순서로 처리한다. systemd를 거치지 않는 직접 CLI 실행에도 같은 guard를 적용한다.

1. 보호된 설정과 정확히 바인딩된 Runner를 검증하고 백업 lock을 획득한다.
2. 이전 성공 기록의 원본·시간 계약을 검증한다.
3. **최대 120초**의 monotonic deadline 안에서 지정된 기존 PostgreSQL만 관찰한다.
4. Docker healthy와 읽기 전용 DB 역할·소유권·권한 검사를 통과한 뒤 AWS 계정을 확인한다.
5. 준비가 끝난 뒤 capture 직전 snapshot 시작 시각을 잡고, capture 안에서 원본을 다시 검사한다.

매 poll은 컨테이너 ID·이미지·Compose label·포트·mount·볼륨 등 정적 대상 계약을 **health 판정보다 먼저** 검사한다. 다른 target이나 변경된 포트·mount를 발견하면 대기하지 않고 거부한다.

| 상태 | 동작 |
|---|---|
| 정확한 기존 컨테이너의 `created`·`exited` | 남은 시간 안에서 외부의 정상 기동을 관찰 |
| `running` + `starting`·`unhealthy` | 남은 시간 안에서 준비 상태를 관찰 |
| `running` + `healthy` | 같은 시간 예산 안에서 읽기 전용 DB guard를 검사한 뒤 준비 완료 |
| paused·dead·restarting·대상 불일치·알 수 없는 상태/health | 즉시 거부 |

**자동 start·재생성·Compose 기동은 없다.** 기존 합성 readiness/source unit을 호출하거나 수정하지 않는다. guard는 정확한 기존 PG가 준비되기를 기다릴 뿐이며, 영구 장애를 자동 복구하는 장치가 아니다.

## 시간 예산·진단·실패 보존

각 Docker/SQL 호출은 **10초와 전체 deadline의 남은 시간 중 짧은 값**으로 제한한다. 최종 SQL도 같은 monotonic 예산을 사용하므로 poll마다 새 120초를 부여하지 않는다. 시간 초과 시 실행 중 child process group을 정리하는 **최대 5초 grace**는 모듈의 120초 대기 예산과 구분한다. service 전체의 `TimeoutStartSec=20min`과 기존 hardening은 유지하며 별도 `ExecStartPre`를 추가하지 않는다.

별도 진단 명령 `ready --wait-seconds 1..120`도 같은 관찰 규약을 사용한다. `--wait-seconds`는 `ready`에만 허용하고 `backup`·`metric`·`validate`에는 허용하지 않는다. `ready`는 AWS 조회·snapshot·업로드·성공 상태 갱신을 수행하지 않는다. 설정 schema는 2를 유지하고 새 필드를 요구하지 않는다.

준비 실패·timeout은 기존 `last-success.json`·주간 슬롯·원격 객체를 바꾸지 않는다. 감시는 별도 lock으로 이전 성공을 계속 평가하며 DB/Docker 조회를 추가하지 않는다. snapshot 시각은 진입 시각이나 준비 시작 시각이 아닌 준비 완료 후 capture 기준으로 기록한다. 진입→준비 완료→capture의 UTC 시각이 뒤로 가거나 이전 성공보다 앞서면 실패하며 이전 성공을 보존한다. 이 시간 후퇴 보완의 최종 회귀 확인은 아래 검증 상태로 구분한다.

## 로컬 검증 상태

| 검사 | 현재 결과 |
|---|---|
| 준비 대기 포함 운영 백업 회귀 | 시간 후퇴 3개를 포함한 최종 **Windows 57개·Linux 57개 통과** |
| 독립 복원 회귀 | **Windows 28개·Linux 28개 통과** |
| 기존 합성 백업·준비 회귀 | **Linux 27개·21개 통과**, 기존 실행자 계약 유지 |
| 최종 native PostgreSQL 통합 | **외부 4항목·원본 37항목·독립 복원 7항목 통과**. 소유 시험 자원 정리·기존 Docker 자원 메타데이터 불변 확인 |
| 자연 정각 시간 창 helper 회귀 | **Windows 7개·Linux 7개 통과**. 경계·최대 대기·중단된 sleep·시각 이상을 fake clock으로 검사 |
| 실제 systemd `Persistent` catch-up | 시간 창 보완 후 최종 격리 QEMU 실행의 **18항목 통과**. 소유 시험 자원 정리·기존 Docker 메타데이터 불변 확인 |

최종 native 통합은 새 합성 PG를 실제 2초 지연 후 재시작해 dump·age·새 백업 성공과 데이터 지문 보존을 확인했다. 2초 준비 timeout 때 이전 성공·주간 기록·객체가 불변이며, PG가 없는 동안 감시가 DB 조회 없이 동작하고 PG 복귀 뒤 백업이 성공하는지도 확인했다. 별도 새 PostgreSQL의 독립 복원·소유 시험 자원 정리·기존 Docker 자원 메타데이터 일치를 검사했다. UTC 시간 후퇴 3개 회귀 중 준비 대기의 2개는 AWS 호출 전 거부하고, identity 조회 중 후퇴 1개는 읽기 전용 STS 조회 한 번 뒤 capture·dump·업로드 전에 거부했다. 모두 이전 성공을 보존했다. S3·CloudWatch는 로컬 simulator이며 실제 AWS 검증을 대신하지 않는다.

Windows TEMP에서 기존 회귀를 직접 실행할 때의 오류와 Linux 비공개 하네스 폴더 배치 실패는 시험 환경을 보완한 뒤 통과했다. QEMU 하네스도 Docker 29의 비실행 tmpfs 표시, 지원 범위 밖 Python 3.11, 종료된 oneshot unit의 정보 정리(GC)에 따른 관측 오판을 보완했다. 실제 앱 장애나 운영 서버 실패로 기록하지 않는다. 기존 Backend·Frontend 전체 테스트·원격 CI는 이번 결과에 포함하지 않는다.

CI는 기존 운영 DB 백업 job에 QEMU 검증 step·관련 script의 경로 필터를, 별도 가짜 전송 회귀 job에는 시간 창 helper 검사를 연결했다. 운영 DB 백업 job 제한은 30분이며 원격 CI 실행 결과는 아직 없다. 변경 workflow의 actionlint 1.7.12 정적 검사와 Node/Python 구문 검사는 통과했다.

## catch-up 검증의 경계

systemd 시험은 호스트 권한·cgroup·Docker socket mount 없는 비특권 Docker의 QEMU TCG guest를 사용한다. 과거 시각은 전용 시험 timer stamp에만 기록하고 호스트·guest의 OS clock은 변경하지 않았다. 최종 통과 실행은 **systemd 252.39·Python 3.13.17·Linux 6.1.0-54·PostgreSQL 17·age 1.3.2**를 사용했다. 실제 Ubuntu 24.04 운영 서버와 동일 OS를 재현한 결과는 아니다.

최종 18항목은 PID 1 systemd의 실제 hourly `Persistent` 자동 실행, PG 기동 전 AWS/성공 기록 없음, PG 지연 기동 후 snapshot·dump·암호화와 원본 보존, timer 재시작 후 55초 동안 중복 성공·객체 변경 없음, 중복 CLI의 lock 적용, 실제 준비 timeout과 이전 성공/객체 보존, PG 복귀 후 원본 보존을 확인했다. catch-up 완료는 timer 활성화 뒤 **20.863초**, timeout 관측은 중복 lock 확인 뒤 **117.961초**였다. 후자는 전체 준비 예산 120초의 일부가 이미 지난 뒤 관찰을 시작한 값이며, catch-up 완료 시간은 전체 서비스 RTO를 뜻하지 않는다.

첫 timer는 운영의 `OnCalendar=hourly`, `Persistent=true`, `AccuracySec=30s`, `RandomizedDelaySec=20s`를 유지한다. timeout용 두 번째 전용 timer만 정확도 1us·임의 지연 0으로 바꾼다. service의 보호 설정은 유지하고 시험용 config/script/state 경로·Python 3.13 실행 경로와 `docker.service` sentinel을 치환한다. 원본은 합성 39테이블·29시퀀스와 수동으로 만든 성공 V1~V12 이력이며 앱 migration 실행은 포함하지 않는다. PostgreSQL·dump·age·systemd는 실제 도구지만 Docker 전달은 self-target shim, S3·CloudWatch는 로컬 simulator다. 실제 AWS나 운영 Docker daemon 검증으로 확대하지 않는다.

느린 guest 준비·부팅 때문에 자연 정각이 시험 도중 들어오는 오판을 막도록 guest 설정 설치 뒤 첫 timer stamp를 쓰기 직전에 안전 시간 창을 확보한다. 다음 정각까지 240초 이하라면 정각 1초 뒤까지 최대 241초를 자연 대기하며, 각 sleep 요청은 30초 이하로 나눈다. Node 시작 전의 시간 창 검사는 제거했다. 최종 VM의 실제 자연 대기는 **0초**였고, 최대 241초 경계는 [helper 회귀](../scripts/operational-catchup.test.py)의 fake clock으로 검증해 **Windows/Linux 각각 7개**를 통과했다. 이 대기와 실행을 포함한 시험용 guest service/QEMU/외부 Docker 호출 한계는 각각 600초/660초/840초다. 운영 백업 service의 20분 한계는 바꾸지 않는다. helper의 모델 검사와 실제 `Persistent` 실행의 증거를 구분한다.

기존 운영 서버의 재부팅 관측은 정상 hourly 실행까지 확인한 결과로 보존한다. 당시 운영 실행자에 이번 guard가 없었던 사실과 정각을 건너지 않은 재부팅의 catch-up/PG 기동 경합 미검증은 여전히 해당 서버·해당 실행의 제한이다. 로컬 guard 구현만으로 실제 서버 보호가 강화됐다고 보고하지 않는다.

## 적용 전 남은 단계

최종 회귀·native 통합·시간 창 helper와 실제 systemd 18항목을 모두 통과해 이번 로컬 검증을 완료했다. 후속 사용자 승인으로 커밋·Push·main 대상 초안 PR 게시·원격 CI 확인을 진행하며 원격 결과는 해당 PR의 최종 HEAD 검사를 따른다. 정식 전환·main 병합·운영 서버 적용·설정/실행자 복구본 갱신·실제 서버 재검증은 별도 승인 단계다. 실제 회원 데이터 이전·공개 전환·전체 서비스 RPO/RTO는 이번 로컬 시험 범위가 아니다. 실제 자격증명·키·덤프·원격 객체 식별자·개별 비밀파일 지문을 공개 문서에 넣지 않는다.

## 합성 검증 재현

Python 3.12 이상·Node 24.18·로컬 Linux Docker와 검증한 공식 age 1.3.2의 `age`/`age-keygen`을 `.local/tools/age-linux-1.3.2/age`에 준비한다. 프로젝트 루트에서 helper 회귀와 두 Docker 하네스를 다음 **순서대로** 실행한다. 자원 보존 기준선을 비교하므로 서로 다른 Docker 통합 시험을 동시에 실행하지 않는다.

```sh
python3 -I -B scripts/operational-catchup.test.py
node scripts/test-operational-backup.mjs
node scripts/test-operational-catchup.mjs
```

첫 QEMU image build는 공식 Debian 패키지 다운로드에 네트워크가 필요하지만 시험 컨테이너와 guest 실행은 네트워크 없이 진행한다. 실제 120초 timeout·55초 중복 관측과 필요한 최대 241초 자연 정각 대기 때문에 수분이 소요된다. 실제 개발 DB·AWS·운영 복구키를 사용하지 않는다.
