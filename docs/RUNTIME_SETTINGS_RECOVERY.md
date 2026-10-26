# 소비자별 권한 설정의 암호화 복구본 갱신

## 승인 범위와 보존

2026-10-25 사용자는 **변경된 설정의 암호화 복구본 갱신·독립 복원**을 승인했다. [권한 분리 구현](RUNTIME_SECRET_PERMISSIONS.md)을 반영한 새 설정 묶음을 별도 보호 폴더에 만들고, 실제 age 인증 복원·참조 대조와 격리된 Linux 권한 재현을 확인했다. 운영 서버 설치·DB 백업/이전·앱 공개·GitHub 반영은 이 작업에 포함하지 않는다.

기존 비활성 운영 원본 **16파일과 기존 암호문**을 유지하고 새 묶음은 별도 버전으로 관리한다. DB 비밀번호·VAPID의 **기존 5개 논리값을 그대로 사용**하며 새 비밀번호나 키로 교체하지 않았다. 새 원본 25파일도 암호화와 복원 전후 동일성을 확인했다. 실제 값·개별 비밀값 SHA·계정/연락처·비공개 절대 경로는 공개 기록에 넣지 않는다.

## 새 묶음의 25파일

| 구분 | 파일 수·내용 |
|---|---|
| 관리용 원본 | DB 비밀번호 2개와 VAPID 공개키·개인키·subject 3개, 총 5파일 |
| 소비자별 런타임 사본 | PG 관리자용 1개·PG init 앱 비밀번호 1개·Backend 앱 비밀번호/VAPID 4개, 총 6파일 |
| 기존 일반 설정 | 운영 env·기본 Compose·Caddy·PG init·앱/entrypoint 참조·릴리스/연결/활성화 조건, 11파일 |
| 권한 분리 자산 | Compose override·Linux 준비/검사 helper·명시적 권한 JSON, 3파일 |

총 **25파일**이며 원래 16파일에 단순히 파일만 추가한 형태와 구분한다. 원래 secret 5개는 새 묶음에서 관리용 원본으로 대응시키고 소비자별 사본 6개를 별도로 둔다. 두 앱 DB 비밀번호 사본은 바이트가 같고 PostgreSQL 관리자 비밀번호와는 다르다.

운영 env는 실제 값 대신 6개 런타임 파일 경로를 가리킨다. 기본 [운영 Compose](../deploy/compose.production.yaml) 뒤에 [권한 override](../deploy/compose.runtime-secrets.yaml)를 적용하는 참조와 [runtime helper](../scripts/runtime-secrets.py)를 함께 보관한다. 기존 `v1.0.0-rc.3` 이미지 pin·전용 DB/앱 역할·정확한 Caddy 프록시 신뢰·worker/Push 5개 OFF·90일 세션/보안 쿠키 계약을 유지한다.

## 실제 암호화와 Windows 인증 복원

[설정 백업 도구](../scripts/configuration-backup.py)의 명시적 **`inactive-production-settings` 범위**와 formatVersion 2를 재사용했다. 새 범위나 기본 복원 허용 정책을 넓히지 않았다. `productionConfigurationCreated:true`, `configurationState:"staged-inactive"`는 비활성 설정의 선언이며 서버 상태 자동 검증으로 해석하지 않는다.

실제 **age v1.3.2**로 새 **13,012bytes 암호문**을 생성하고 기존 폴더를 덮어쓰지 않는 독립 Windows 폴더에 인증 복원했다. 복구 개인키는 기존 PC 보호 위치에서 age가 사용했으며 archive·Linux 검사 컨테이너·운영 서버로 보내지 않았다. 운영 자격증명이나 기존 복구키를 새 키로 교체하지 않았다.

검증은 다음을 구분한다.

| 검증 | 실제 결과 |
|---|---|
| age 암호화·Windows 독립 복원 | **114항목 통과**. 실제 인증 복원, 25파일 SHA/크기·Windows native ACL과 원본 보존 확인 |
| 원본 정적 연결 검사 | **154항목 통과**. 원본 5개/런타임 6개 대응·VAPID P-256/ES256·env/릴리스/권한 JSON·Compose 참조 확인 |
| 복원 사본 정적 연결 검사 | 동일한 **154항목 통과**. PG 2개·Backend 4개·Frontend/Caddy 0개 secret, 중복 target 부재·파일 경로 대조 |
| 격리 Linux 권한 재현 | **96항목 통과**. 별도 tmpfs에서 고정 권한표 적용·6사본 재생성·helper `check`·바이트 대조 |
| 보존·격리 | 새 원본 25파일·기존 원본 16파일·신규/기존 암호문 보존. 검사 컨테이너와 임시 Windows 복원 26파일 정리, 기존 Docker 기준선 유지 |

위 수치는 서로 다른 검사 범위이므로 하나의 서비스 복구 테스트 수나 전체 RTO 결과로 합산하지 않는다. 최종 증빙에서 임시 Windows 복원 **25파일과 manifest 1개, 총 26파일**을 개별 삭제하고 임시 디렉터리를 정리했음을 확인했다. 보호된 새 원본과 암호문·검증 영수증은 보존한다.

## Windows 메타데이터와 Linux 권한 재현의 차이

Windows에서 수집한 manifest의 uid/gid/mode는 **수집 당시의 출처 정보**다. Windows native ACL 보호와 Linux 소비자 소유권은 별도 조건이며 manifest의 uid/gid만으로 `999`·`10001` 소유권이 자동 복원됐다고 표현하지 않는다. 현재 범용 restore 도구는 소유권을 바꾸는 `chown` 기능을 제공하지 않는다.

Linux 검증은 인증 복원된 사본을 **새 tmpfs·네트워크 없음·호스트 디렉터리 mount 없음**인 검사 컨테이너에 전달해 수행했다. 복구 개인키는 전달하지 않았다. 검토한 고정 권한표로 관리용 원본의 root 소유 `0600`과 디렉터리 `0700`을 재현한 뒤 helper로 런타임 사본 6개를 새로 생성했다. PG 관리자 파일은 `0:0`, PG 앱 비밀번호 사본은 검증한 `999:999`, Backend 4파일은 `10001:10001`과 `0400`으로 확인했다. archive에 들어 있던 런타임 사본과 재생성 결과의 바이트도 대조했다.

이는 **Windows 인증 복원 + 명시적인 Linux 권한 재현**이다. 실제 운영 경로에 설치하거나 범용 restore가 모든 uid/gid를 적용한 결과가 아니다. 이번 검사에서는 DB·Spring Boot·Flyway·Push를 기동하지 않았고 기존 DB·볼륨도 사용하지 않았다.

## 보관과 다음 단계

새 암호문은 PC의 보호 위치에 보관한다. 사용자가 이전에 확인한 **PC 밖 복구키 사본**은 계속 유효한 보관 기록이지만, **이번 새 암호문을 PC 밖에 복사·확인했다는 기록은 아직 없다.** 새 설정 암호문과 복구키는 서로 분리해 관리한다.

기존 준비·합성 설정 30파일 archive, 추가 자동 복구 3파일 archive, 이전 비활성 16파일 archive와 새 25파일 archive는 범위·버전별로 구분한다. 서로를 삭제하거나 하나의 전체 DB/서비스 복구 자료로 합치지 않는다. 새 archive에는 회원·팀·예약·세션 DB 덤프가 없다.

다음은 합성 전용 백업과 구분한 **운영 DB 백업·독립 복원·감시 구현**이다. 실제 설정 설치·실제 데이터 이전·쓰기 허용·공개 전환은 [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md)의 검증과 사용자 승인 조건을 따른다. 현재 원격 CI·운영 VM 설치·GitHub 게시는 수행하지 않았다.

관련 기준: [비활성 운영 설정](INACTIVE_PRODUCTION_SETTINGS.md), [비밀파일 권한](RUNTIME_SECRET_PERMISSIONS.md), [백업 운영 계획](OPERATIONS_BACKUP_PLAN.md), [검증 계획](TEST_PLAN.md).
