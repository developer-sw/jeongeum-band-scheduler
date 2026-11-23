# PostgreSQL 예약 경합과 정합성 검증

2026-11-23 KST · 기준 소스 `1e0236bc358d1f5be6408895e0abde26b750d86c`

## 문제와 접근

가입한 여러 회원이 같은 합주실을 이용하므로, 같은 악기나 블록에 동시에 요청이 들어올 때 승자 한 건의 예약만 온전히 저장돼야 한다. 개인 예약은 악기별로 공유할 수 있고 팀 예약은 여섯 악기를 모두 점유한다. 예약을 취소·변경하거나 교환 승인이 실패한 뒤에도 원본 이력, 점유와 사용 한도가 실제 결과에 맞아야 한다.

기존 구현은 PostgreSQL 트랜잭션의 공통 잠금 순서로 요청을 조정하고, 활성 예약·악기 점유의 부분 고유 인덱스로 중복을 차단한다. 팀의 여섯 점유와 하루 한도 합산은 서비스에서 처리한다. 이 구조를 검증하기 위해 기존 두 요청 경합에 반복 실행과 SQL 검사 결과를 추가했다. PostgreSQL 대체 DB나 Repository mock을 사용하면 저장 제약과 실제 잠금 동작을 함께 입증하기 어렵다. [예약 잠금·재시도](../RESERVATION_CHANGES.md) · [점유·한도 스키마](../../backend/src/main/resources/db/migration/V4__create_basic_reservations.sql)

## 환경과 재현

- 실제 PostgreSQL 17.11 이미지 digest와 Testcontainers 2.0.5를 기존 테스트에서 재사용했다. 각 테스트 클래스는 임의 자격증명·별도 DB의 PostgreSQL 컨테이너와 임시 포트의 Spring Boot 앱을 사용한다. V1~V13을 적용한다.
- 실행 환경은 Windows, Temurin JDK 21.0.11, Gradle 9.8.1, Docker Engine 29.9.3 Linux amd64였다. Docker 엔진에서 보고한 가용 사양은 논리 CPU 16개와 메모리 약 15.3 GiB다. 애플리케이션의 Hikari 풀은 최대 5개 연결이다.
- 합성 시각은 서울 기준 2026-10-30 11:00으로 고정했다. 반복 시나리오마다 열린 날짜 20개를 준비하고 한 날짜에 한 번씩 실행했다. 로그인·CSRF 준비와 사전 예약은 경합 요청 집계에서 제외했다. 각 테스트 시작 시 해당 Testcontainers DB의 예약 상태를 초기화한다.
- 최대 30개 HTTP 작업이 시작점에 모인 뒤 요청한다. 시작 대기는 5초, HTTP 요청은 25초, 한 묶음은 30초로 제한한다. 주 검증 테스트에는 180초 또는 240초의 종료 제한이 있다. 변경·교환·고정 배정의 두 요청은 기존 도우미가 공통 가드의 PostgreSQL 잠금 대기를 관측한 뒤 진행시킨다.

저장소의 `backend` 디렉터리에서 Docker Linux 엔진이 실행 중일 때 다음 명령을 실행한다. 최초 실행에는 Gradle 의존성과 PostgreSQL 이미지를 내려받을 수 있어야 한다.

```powershell
.\gradlew.bat --no-daemon test `
  --tests '*ReservationIntegrationTest.repeated*' `
  --tests '*ReservationIntegrationTest.simultaneousPersonalRequestsForLastHourCannotExceedDailyQuota' `
  --tests '*ReservationIntegrationTest.simultaneousDifferentTeamCreatorsShareTheSameRemainingDailyQuota' `
  --tests '*ReservationChangeIntegrationTest.concurrentChangeAndCreateForSameInstrumentHaveOneWinnerAndPreserveLosingSource' `
  --tests '*ExchangeIntegrationTest.concurrentApprovalAndThirdPartyCreateKeepOneWholeDestinationState' `
  --tests '*RecurringAssignmentIntegrationTest.concurrentFixedSeriesAndManualCreateDoNotLeavePartialWeeklyClaims'
```

의존성이 캐시된 실행에서는 `--offline`을 사용했다. 다시 실행하면 JUnit XML은 `backend/build/test-results/test`에 생성된다. `RESERVATION_RACE_EVIDENCE` 줄은 SQL 검사와 응답 검증이 모두 통과한 시나리오에서만 출력된다. 여기에는 합성 실행의 건수·시나리오 이름·정해진 오류 코드만 포함하며, 실패한 실행은 JUnit 결과에서 판정한다.

## 집중 실행에서 관측한 결과

기존 마지막 60분 한도 테스트 두 건에 집계 출력을 연결하기 전에 경합 테스트를 집중 실행했다. **9개 테스트 실행, 123회 경합, HTTP 요청 966개**였다. 요청 중 성공 162개, 예상 충돌 804개, 그 외 오류 0개였다. Gradle 전체 경과는 1분 20초였다. 아래 SQL 집계는 각 테스트의 독립적인 합성 상태를 보여준다.

| 시나리오 | 동시 요청 × 반복 | HTTP 성공 / 예상 충돌 / 기타 | 최종 예약 상태 | 활성 / 해제 점유 | 한도 원장 행 / 순합계 |
|---|---:|---:|---|---:|---:|
| 같은 슬롯·악기 | 2 × 20 | 20 / 20 / 0 | 확정 20 | 20 / 0 | 20 / 1,200분 |
| 같은 슬롯·악기 | 10 × 20 | 20 / 180 / 0 | 확정 20 | 20 / 0 | 20 / 1,200분 |
| 같은 슬롯·악기 | 30 × 20 | 20 / 580 / 0 | 확정 20 | 20 / 0 | 20 / 1,200분 |
| 팀과 개인 | 2 × 20 | 20 / 20 / 0 | 확정 20 (팀 9, 개인 11) | 65 / 0 | 20 / 1,200분 |
| 취소와 같은 슬롯 신규 예약 | 2 × 20 | 39 / 1 / 0 | 원본 취소 20, 신규 확정 19 | 19 / 20 | 59 / 1,140분 |
| 동일 키, 한 사용자 두 세션 | 2 × 20 | 40 / 0 / 0 | 확정 20 | 20 / 0 | 20 / 1,200분 |
| 변경과 목적지 선점 | 2 × 1 | 1 / 1 / 0 | 변경 승리, 원본 교체 1, 후속 확정 1 | 1 / 1 | 3 / 60분 |
| 교환 승인과 제3자 선점 | 2 × 1 | 1 / 1 / 0 | 교환 승리, 원본 교체 2, 후속 확정 2 | 7 / 7 | 5 / 60분 |
| 고정 배정과 일반 예약 | 2 × 1 | 1 / 1 / 0 | 일반 예약 확정 1, 고정 배정·회차 0 | 1 / 0 | 1 / 60분 |

반복 시나리오별 DB 검사에서 **활성 악기 점유 중복, 예약 상태와 점유 수 불일치, 주체별·날짜별 0~120분 범위 위반, 예약과 원장 잔액 불일치가 모두 0건**이었다. 같은 슬롯에서는 매회 성공 응답의 예약 ID가 해당 날짜의 유일한 확정 예약과 일치했다. 동일 키로 받은 `201` 40개는 매회 같은 예약을 반환했으며, DB에는 예약·점유·한도 원장·업무 작업·멱등 기록이 각각 20개였다. 취소 경합의 성공 39개는 취소 20개와 신규 19개다. 원본 20개는 모두 취소 상태이고 60분 부과와 60분 반환 이력이 남았다.

변경·교환·고정 테스트는 각각 어느 쪽이 먼저 커밋되는지에 따라 두 결과를 허용한다. 변경·교환이 지면 원본과 기존 점유를, 고정 배정이 이기면 31일 안 주간 회차 다섯 건과 각각의 여섯 점유를 검사한다. 이 표는 위 실행에서 선택된 순서만 보여준다. [기본 예약 테스트](../../backend/src/test/java/kr/jeongeum/scheduler/ReservationIntegrationTest.java) · [변경](../../backend/src/test/java/kr/jeongeum/scheduler/ReservationChangeIntegrationTest.java) · [교환](../../backend/src/test/java/kr/jeongeum/scheduler/ExchangeIntegrationTest.java) · [고정 배정](../../backend/src/test/java/kr/jeongeum/scheduler/RecurringAssignmentIntegrationTest.java)

## 백엔드 전체 회귀에서 관측한 결과

같은 PC에서 `./gradlew.bat --offline --no-daemon test`로 414개 테스트를 실행했다. 실패·오류·건너뜀은 각각 0건이었고 Gradle 전체 경과는 13분 20초였다. 경합 집계 11개에는 125회, HTTP 요청 970개, 성공 165개, 예상 충돌 805개, 그 외 오류 0개가 기록됐다. 같은 슬롯과 동일 키의 결과 및 네 가지 DB 위반 수는 집중 실행과 같았다. 팀과 개인 경합에서는 팀 7건과 개인 13건이 확정되어 점유 55행이 남았다. 취소 경합에서는 원본 취소 20건과 신규 확정 20건이 성공해 활성·해제 점유가 각각 20행, 한도 원장이 60행·1,200분으로 집계됐다.

| 마지막 60분 한도 경합 | 동시 요청 × 반복 | HTTP 성공 / 예상 충돌 / 기타 | 최종 확정 예약 / 활성 점유 / 한도 원장 | 최종 사용량 |
|---|---:|---:|---:|---:|
| 한 개인의 두 요청 | 2 × 1 | 1 / 1 / 0 | 2 / 2 / 2 | 120분 |
| 같은 팀의 다른 예약자 두 명 | 2 × 1 | 1 / 1 / 0 | 2 / 12 / 2 | 120분 |

두 사례 모두 사전 예약 60분에 경합 승자의 60분만 더해졌다. 패자는 `DAILY_LIMIT_EXCEEDED`를 반환했고 DB의 예약·점유·원장에는 패자의 요청이 반영되지 않았다.

## 증거의 범위

30개는 동시에 시작하는 HTTP 요청 수다. 서버와 연결 풀의 스케줄링에 따라 PostgreSQL에 도달하는 순서는 다를 수 있다. 이 실험은 업무 불변식을 판단하기 위한 반복 회귀 테스트다. 실행 시간은 테스트의 제한과 재현 환경을 설명하는 데 사용한다. 실제 운영의 처리량, 응답 시간 분포, 동시 접속 규모는 같은 조건을 명시한 별도 성능 실험의 대상이다.
