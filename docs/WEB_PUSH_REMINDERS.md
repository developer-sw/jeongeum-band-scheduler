# Web Push·합주 시작 1시간 전 알림

최신 반영: 2026-10-16 사용자 승인으로 후속 UI·Push 클릭 복구·실기기 검증 기록을 포함한 [PR #19](https://github.com/developer-sw/jeongeum-band-scheduler/pull/19)를 정식 전환·main에 병합했다(`0d660ae`). 기능 HEAD `27cde27`의 Push·PR CI 4개 검사가 성공했다(Backend 352건·Frontend 289건). 아래 초안·별도 승인 대상 표시는 당시 기록이며, 모든 OS·운영 환경의 수신을 검증한 것으로 확대하지 않는다.

최신 상태: 2026-10-15 / Asia/Seoul. Frontend 21파일·289건과 타입·lint·production 빌드, 최종 worker 회귀를 확인했다. 합성 환경에서 PC·iPhone 중요 공지 수신·열기, iPhone 시작 1시간 전 알림과 예약 열기, 기기별 로그아웃 및 회원 탈퇴 후 모든 연결 기기의 로그인·새 알림 차단을 사용자와 확인했다. 후속 UI·클릭 복구와 결과 문서는 사용자 승인으로 기존 초안 PR #19에 반영하며 최신 CI는 그 커밋별 결과를 따른다. 정식 전환·main 병합은 별도 승인 대상이다. 실제 관찰 범위·재발 이력·미검증 조건은 아래 후속 기록에 구분한다.

초기 구현 기준: 2026-10-14 / 구현 브랜치: `codex/web-push-reminders` / 시작 main: `9c05c43`. 구현과 전체 회귀·격리 브라우저 검증 후 사용자 승인으로 새 V9 백업·실제 격리 복원·개발 DB V10 한 건 적용·최신 서버 기동을 완료했다. 커밋·Push·초안 PR·원격 CI를 마친 뒤 추가 승인으로 PR #17을 정식 전환·main 병합했다(`f39969a`). 기존 LAN 설정·다이어리 UI·업무 데이터·관리자·예약·볼륨을 보존했다. 그 당시에는 외부 Push 서비스와 실제 휴대폰 수신을 검증하지 않았으며 후속 결과는 위 최신 상태와 아래 실기기 기록을 따른다.

## 확정 정책과 구현 선택

- 기존 예약·반복 회차·교환 사건과 최초 중요 공지 알림을 기기별 발송 작업으로 연결한다. 관리자라는 이유로 모든 예약을 받거나 일반 공지·수정·재고정을 재알림하지 않는다.
- 시작 알림 계획은 블록 시작 한 시간 전이 처리 당시보다 엄격히 미래일 때만 등록한다. 이미 지난 시각·진행 중 예약은 소급 계획을 만들지 않는다. 알림함의 시작 안내는 계획 시점에 미리 만들지 않는다.
- 변경·교환·취소는 이전 계획·대기 시작 발송을 무효화하고 최종 예약의 새 계획을 같은 트랜잭션에서 저장한다. 예약 계보의 최초 root ID 집합과 실제 시작 시각을 이용해 같은 논리 회차의 중복을 억제한다. 60분 두 건은 블록별 계획을 유지한다.
- **2026-10-14 사용자 확정:** 로그아웃하면 해당 로그인 세션에 연결된 기기의 Push만 중단한다. 다른 기기의 구독은 유지한다. 동일 endpoint의 다른 계정 연결은 binding version을 변경하고 이전 대기 작업을 종료한다.
- 로그인 만료 자체를 기기 구독 해제로 처리하는 별도 정책은 확정하지 않는다. 서버 발송은 활성 회원·현재 팀 자격·구독 binding을 검사하며, API 조회에는 기존 90일 세션·현재 권한을 적용한다.
- worker는 현재 예약·버전·활성 회원·현재 팀 소속·기기 binding을 다시 확인한다. 존속하는 팀의 최초 작성자 비활성화만으로 다른 조원의 시작 알림을 종료하지 않는다.
- PostgreSQL 작업 행과 worker를 사용한다. 외부 Push 호출은 예약/디렉터리 잠금 트랜잭션이 끝난 뒤 실행한다. 전달 성공은 Push 서비스 수락이며 실제 OS 표시를 보장하지 않는다. 네트워크 호출과 사용자 상태 변경 사이의 짧은 경합 및 발송 후 기록 전 중단에서 외부 중복 가능성이 남으며, 알림 tag와 기기 binding·클릭 후 현재 API 조회로 완화한다.

## 데이터·API

새 `V10__web_push_and_reminders.sql`은 `push_subscriptions`, `notification_jobs`를 추가하고 기존 notifications 유형에 `START_REMINDER`를 추가한다. V1~V9 migration과 기존 예약·알림 행은 수정하지 않는다.

`REMINDER_PLAN`은 예약 ID·예상 버전·정렬된 논리 root 집합·주체·실제 시작·예정 시각을 보존한다. 처리 완료는 `COMPLETED`, 무효화는 `CANCELED`다. 시작 후 발견한 계획을 보내지 않는다. 시작 전 지연된 기존 계획은 현재 상태를 확인해 처리한다. 시작·주기적 복구는 기존 미래 확정 예약의 누락 계획만 최대 200개씩 보완하며 과거 CREATED 알림을 재생성하지 않는다.

`PUSH_DELIVERY`는 이미 생성한 알림 ID·구독 ID·binding version을 보존한다. 구독별 unique job key, claim/lease와 만료 복구, 제한된 재시도·redacted 오류 분류를 사용한다. 404/410은 해당 만료 구독을 중단하고 429/일시 실패는 제한된 backoff로 재시도한다. 취소한 작업을 lease 복구로 되살리지 않는다.

| 경로 | 계약 |
| --- | --- |
| `GET /api/v1/push/public-key` | 활성 회원에게 `{enabled, publicKey}`; 비밀키 제외 |
| `GET /api/v1/push-subscriptions` | 본인 기기의 ID·이름·상태·currentDevice·bindingVersion·시각; endpoint/암호 키 제외 |
| `POST /api/v1/push-subscriptions` | `{endpoint, keys:{p256dh,auth}, deviceName}`; 인증된 본인·현재 세션에 연결 |
| `DELETE /api/v1/push-subscriptions/{id}` | 본인 기기만 해제, 멱등 204 |

쓰기 API는 기존 인증·CSRF·현재 회원 상태를 검증한다. endpoint는 기본 FCM·Mozilla·Apple Push 호스트의 HTTPS만 허용하고 키 형태·곡선·길이를 검사한다. 발송 시 공개 주소 DNS 검증·redirect 금지·연결/응답 timeout을 적용한다. endpoint·키·세션 식별자는 응답·로그에 남기지 않는다.

## 화면·설치

`/notifications/settings`에서 현재 기기의 알림 켜기/끄기와 본인 기기 관리를 제공한다. 페이지 진입만으로 권한을 요청하지 않는다. 미지원·HTTP·미설정 서버·권한 거절·구독 실패 상태를 구분하며 알림함과 예약 기능은 계속 사용할 수 있다.

service worker는 Push 표시와 내부 이동을 처리하며 회원 API·개인화 HTML을 오프라인 캐시하지 않는다. 저장한 현재 구독 ID/binding version과 다른 메시지는 이전 계정의 제목·본문을 표시하지 않는다. Push 클릭은 내부 경로로 이동하고 현재 계정·권한·예약 상태를 다시 조회한다.

휴대폰에서 현재 LAN HTTP 주소는 화면 확인용이다. 실제 Push는 신뢰된 HTTPS 환경에서 확인해야 한다. iPhone은 홈 화면에 추가한 웹 앱과 사용자의 알림 버튼 동작이 필요하다. [MDN PushManager](https://developer.mozilla.org/en-US/docs/Web/API/PushManager), [WebKit 홈 화면 Web Push](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/), [Next.js PWA](https://nextjs.org/docs/app/guides/progressive-web-apps)

## CSRF 동시 초기화 보완

동일 JDBC 세션의 첫 CSRF 토큰을 여러 탭/서버가 동시에 만들면 각 응답이 다른 토큰을 반환하거나 세션 attribute INSERT가 충돌할 수 있었다. 기존 세션 attribute의 원자적 INSERT와 승자 토큰 조회를 사용하고, JDBC 세션 immediate flush로 새 세션 행·회전 순서를 보장한다. 랜덤 토큰·기존 직렬화·XOR 처리·로그인 회전·로그아웃·90일 유지 계약은 보존한다. 프론트는 진행 중인 토큰 조회만 공유하고 호출자별 abort와 인증 세대 변경을 분리한다. mutation을 자동 재전송하지 않는다.

## 실행·검증 상태

Push와 시작 worker는 기본 비활성이다. 승인된 DB 적용 후 시작 worker는 `jeongeum.reminder.worker.enabled=true`, Push worker는 `jeongeum.push.worker.enabled=true`로 켠다. 실제 Push는 추가로 `jeongeum.push.enabled=true`와 서버 환경의 `APP_VAPID_PUBLIC_KEY`, `APP_VAPID_PRIVATE_KEY`, `APP_VAPID_SUBJECT`가 필요하다. 비밀키를 Git·문서·브라우저에 넣지 않는다. 운영 도메인·외부 발송·실기기 수신 완료를 이번 로컬 구현 결과로 대신하지 않는다.

아래 전체 테스트 결과는 격리 환경과 최신 소스 기준이다. 실제 개발 DB에는 사용자 승인 후 V10만 적용했고 합성 예약·구독 데이터는 넣지 않았다. 적용 과정은 마지막 절에 기록한다.

## 2026-10-14 최종 실행 결과

| 검증 | 실제 결과 |
| --- | --- |
| 전체 Backend | JUnit **18개 suite·325건**, 실패·오류·skip 모두 0. 기존 인증·90일 유지·회원/팀·예약·변경·교환·고장·공지·알림 회귀와 PostgreSQL Testcontainers 포함 |
| 시작 알림 | PostgreSQL **17건** 통과. 정확한 due·이미 지난 due·서울 시간 경계, 변경/취소/교환/반복 회차·현재 팀 수신자, 계보/시각 중복, 트랜잭션 rollback·lease 복구 검증 |
| 기기 구독·발송 | PostgreSQL **30건** 통과. 소유권·다중 기기·현재 세션 로그아웃·늦은 재등록 경합·계정 재연결·만료/영구 실패/일시 실패·제한 재시도·잠금 밖 발송·현재 예약/권한 재검증 |
| VAPID·안전한 발송 요청 | **7건** 통과. 실제 생성한 aes128gcm 암호문 복호화·ES256 VAPID 서명 검증, 키/curve·endpoint/DNS/redirect·payload/TTL·Retry-After 검사. 외부 provider 호출은 하지 않음 |
| 실제 scheduler·재시작 | 독립 Spring JVM **2건** 통과. 격리 DB의 제어 Clock·대체 sender로 실제 scheduler 등록, 두 JVM의 단일 처리·재시작 복구·취소 계획 미재실행 확인 |
| scheduler 응답성 | **6건** 통과. 발송을 전용 executor로 분리, 단일 공용 scheduler의 다른 작업 진행, 중복 batch 차단, 종료 interrupt·최대 5초 대기, 다음 tick 복구 확인 |
| CSRF 동시성 | PostgreSQL **5건** 통과. 로그인 직후 준비 없는 최초 동시 요청, 같은 세션의 원자적 첫 토큰, 독립 JVM·재시작, 로그인 ID 회전·이전 토큰 거절·로그아웃 정리. CSRF 검사·XOR masking 유지 |
| 전체 Frontend | **19파일·214건** 통과. `npm.cmd run test`, `typecheck`, `lint` 모두 exit 0 |
| production build | 원본과 SHA256이 일치하는 **150파일** 격리 복사본에서 `APP_API_ORIGIN=http://127.0.0.1:8084 npm.cmd run build -- --webpack` exit 0. 20개 route(404/manifest 포함), build ID `uaCKP8__i-fBuze09g8ex`; 원본 개발 `.next` 보존 |
| Spring Boot JAR | 전체 `test bootJar` exit 0. 실제 독립 JVM 검증에도 최신 JAR 사용 |
| 독립 코드 검토 | 최초 binding version 0 거부(P1), 브라우저 정리 실패가 서버 로그아웃을 막는 경로(P2)를 발견·수정·회귀 검증. 수정 후 검토 범위의 추가 확정 P1/P2 없음 |

첫 전체 Backend 실행은 새 V10을 빠뜨린 Bootstrap 테스트의 V1~V9 기대값 1건이 실패했다. 성공 migration 기대값을 V1~V10으로 수정한 뒤 **전체 325건을 다시 실행해 통과**했다. 적용된 V1~V9 SQL은 바꾸지 않았다. 테스트 종료 시 이미 내려간 격리 컨테이너에 대한 세션 청소/Hikari 연결 정리 경고가 있었고, 최종 JUnit 실패·오류는 없었다.

최초 기기의 binding version은 0이다. SW는 이를 허용하고 잘못된 ID·음수·비정규 버전은 거절한다. 첫 연결/개인 표시/클릭과 재연결 후 이전 메시지 내용 차단을 추가 검증했다. 로그아웃은 브라우저 정리와 독립적으로 서버에 즉시 요청한다. SW 저장 실패·응답 timeout에도 unsubscribe를 시도하며 서버 실패를 성공으로 표시하거나 자동 재전송하지 않는다. 서버 성공 후 브라우저 정리가 실패하면 로그인 화면에서 공용 기기의 알림 권한/사이트 데이터 정리 안내를 표시한다.

## 실제 브라우저 확인과 제한

합성 계정·전용 `push_ui_qa` PostgreSQL·8084 backend·3004 production frontend를 사용했다. V10은 이 DB와 Testcontainers에만 적용했고 실제 개발 쿠키와 구분한 QA 쿠키를 사용했다. sender·worker는 UI 환경에서 비활성으로 두고 외부 서비스/실제 회원에게 발송하지 않았다.

- PC의 공지·알림 메뉴, 모바일 더보기·내 계정·알림함에서 **`/notifications/settings`** 접근을 확인했다.
- 합성 기기 두 개를 서버에 등록한 뒤 브라우저에서 휴대폰 기기만 해제했다. 데스크톱은 켜짐, 휴대폰은 꺼짐으로 확인했다.
- 실제 알림함 읽음 처리로 안 읽은 1건→0건을 확인했다. 예약 생성 입력·변경 폼·취소 확정 전 화면·교환 선택 폼과 기존 로고/다이어리/하단 문구를 확인했다. 브라우저에서 예약 변경·취소·교환 확정을 제출하지 않았으며 해당 업무 연동은 PostgreSQL 전체 회귀로 검증했다.
- 최신 production에서 모바일 로그아웃→다른 합성 회원 로그인→이전 계정/기기 목록 비노출을 확인했다. 정리 실패 안내는 명시적인 시험 URL로 화면을 확인하고 실제 실패·5초 timeout·서버 실패는 자동 회귀로 검증했다.
- **320/390/768/1440px**에서 알림 설정 화면의 scroll width와 client width가 같았다. 최신 화면 console error 0. 실제 OS 알림 권한을 허용하거나 현재 브라우저를 외부 Push에 구독하지 않았다.
- viewport 검증은 실제 iOS/Android 수신 검증이 아니다. 권한 거절·미지원·HTTP·iOS 설치 상태는 기능 검사/자동 회귀로 확인했으며 물리 휴대폰·OS 알림·provider 수락은 미검증이다.

실제 휴대폰 확인은 신뢰된 HTTPS 시험 환경에서 승인된 시험 계정/기기로 진행한다. iPhone/iPad는 iOS/iPadOS 16.4 이상, 홈 화면의 standalone 웹 앱과 사용자 버튼 동작에 따른 허용이 필요하다. HTTP LAN 주소는 화면 확인용이며 Push 수신 증거로 취급하지 않는다. [WebKit 공식 조건](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/), [MDN 보안 컨텍스트 조건](https://developer.mozilla.org/en-US/docs/Web/API/PushManager)

## 개발 DB 적용 전 보존·재개 기록 (2026-10-14 16:10)

최종 읽기 전용 대조에서 **개발 DB 36개 테이블 모두의 건수·행 해시가 재개 직전과 같았다**. 세션·세션 attribute·booking_days도 포함한다. V1~V9 성공·활성 관리자 1명·예약 0건·`jeongeum-dev_postgres_data` 유지, V10/new Push 테이블은 실제 DB에 없다.

기존 `backups/local-v8-before-v9-20261013-195210-9798160.dump`는 126721 bytes이며 SHA256 `F5405F60A4F827021AEDC253763BC1DAEE3479089CBC857BE8512F7202F8F8C7`로 기존 기록과 같았다. 초기화·복원·삭제·덮어쓰기는 하지 않았다. README와 V1~V9 파일, 로고·다이어리 CSS·브랜드 문구는 보존했다. LAN 변경 `Start-Frontend.ps1`, `next.config.ts`, `DEV_ENVIRONMENT.md`도 유지했다.

브랜치 `codex/web-push-reminders`, HEAD `9c05c432d9a075c478aacb5ef266df07b1348131` 유지. 수정 30개·신규 43개 파일은 미커밋이며 staged 0이다. 주요 범위는 backend `push/`, reminder/CSRF와 V10·회귀 테스트, frontend 설정/worker/manifest/로그아웃/CSRF와 테스트, 관련 설계 문서다. 실제 `.env`·VAPID 개인키·DB 덤프·회원 데이터·검증 로그는 Git에 넣지 않았다.

재개용 `.local/push-resume-checkpoint.json`과 최종 `push-backend-final.json`, `push-frontend-final.json`, `push-browser-final.json`, `push-preservation-final.json`, `push-git-final.json`에 실행·변경 파일·제한을 기록했다. 화면 증거는 `push-settings-devices-final-1440.jpg`, `push-settings-member-final-390.jpg`, `push-logout-cleanup-notice-final-390.jpg`다. 이 파일들은 로컬 검증 자료이며 Git 대상이 아니다.

소유한 QA 서버·임시 컨테이너·새 브라우저 탭을 종료했고 viewport를 복원했다. Docker와 기존 개발 PostgreSQL은 healthy로 유지한다. 재개 시부터 중지돼 있던 실제 3000/8080 서버는 중지 상태이며, 새 backend를 시작해 V10이 자동 적용되는 일을 피했다.

이 시점에 남겼던 승인 단계는 다음과 같다. 이후 사용자 승인과 실행 결과는 아래 절을 따른다.

1. 현재 **V9의 새 백업**을 별도 이름으로 생성·checksum 확인하고 격리 복원 검사 후 개발 DB V10 한 건 적용·최신 backend 재시작·기존 데이터 대조. 기존 백업은 덮어쓰지 않는다.
2. 검증된 변경의 커밋·Push·main 대상 초안 PR·원격 CI 확인. 정식 PR 전환·main 병합은 이후 별도 승인.
3. 승인된 HTTPS 시험 계정/실기기로 알림 허용·수신·클릭·로그아웃/다른 기기 유지 확인. 실제 수신 완료 전 운영 준비 완료나 외부 exactly-once 전달을 주장하지 않는다.

## 사용자 승인 후 V10 적용·실제 서버 기동 (2026-10-14)

사용자가 **새 V9 백업·V10 적용·최신 서버 기동, 커밋·Push·초안 PR·CI 확인**을 명시 승인했다.

- 새 `backups/local-v9-before-v10-20261014-161702-5734486.dump`는 **139986 bytes·archive 289개**, SHA256 `6C81458057B0E8479EA5E8C704AFC7B6F5E2731CAAD790CA02E850237CED7238`다. 기존 백업은 덮어쓰지 않았다.
- 네트워크 `none`·호스트 포트 없음·tmpfs의 임시 PostgreSQL에 실제 `pg_restore --exit-on-error` 복원했다. V1~V9·36개 테이블의 건수/행 해시·기존 세션·시퀀스가 백업 직전과 같았다. 원본 DB도 백업 동안 같았고 소유 임시 컨테이너만 정리했다.
- 기존 `Start-Backend.ps1`에서 Flyway가 migration 10개를 검증하고 **9→10 한 건**을 정상 적용했다. V1~V9 이력의 전체 행 해시는 그대로다. 새 테이블은 `push_subscriptions`, `notification_jobs` 두 개뿐이며 모두 0건이다.
- 기동 직후 기존 **32개 업무 테이블**, 관리자 1명·예약 0건·볼륨, 세션 13건·attribute 14건·날짜 19건을 보존했다. 연결 검사에서 익명 세션 1건만 추가되어 세션 14건·attribute 15건이 됐고 업무 데이터와 날짜는 그대로다. 실제 계정 로그인이나 예약/구독 생성은 하지 않았다.
- Backend `127.0.0.1:8080`, Frontend `0.0.0.0:3000`, 현재 Wi-Fi `192.168.0.47:3000`으로 기동했다. localhost와 LAN 주소의 API health `UP`·서울 시간대, Next.js 프록시·HttpOnly 세션·CSRF 없는 403·인증 없는 401을 확인했다. 홈·로그인·알림 설정·manifest·SW HTTP 200, SW `no-store`·`nosniff`를 확인했다. 같은 PC의 LAN 요청 검사는 물리 스마트폰 접속/수신 검사와 다르다.

이번 프로세스에서는 `JEONGEUM_REMINDER_WORKER_ENABLED=true`, `JEONGEUM_PUSH_WORKER_ENABLED=true`, **`JEONGEUM_PUSH_ENABLED=false`**를 적용했다. 시작 알림 worker는 활성이고 발송 worker는 sender 비활성이므로 외부 발송이나 발송 claim을 수행하지 않는다. VAPID 비밀키를 생성/추가하거나 실제 기기에 시험 알림을 보내지 않았다. 환경변수는 해당 프로세스에만 적용하며 다음 수동 기동에서도 같은 설정이 필요하다.

기존 LAN 실행 옵션·AllowedDevOrigins를 유지했고 방화벽 규칙은 바꾸지 않았다. 방화벽 상세 조회는 Windows 권한 거부로 확인하지 못했다. LAN 주소 응답은 통과했으며 다른 기기의 실제 접속은 별도 확인 대상이다.

기존 LAN 변경은 커밋 `9977da7`, 기능은 `210d504`로 분리했다. 새 백업·복원·실제 적용·기동·보존 증빙은 Git에서 제외한 `.local/push-v10-backup-latest.json`, `push-v10-startup.json`, `push-v10-smoke.json`, `push-v10-server.json`에 기록했다. 기능 HEAD의 [Push CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38528743841)와 [PR CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38528767465)가 모두 성공했다. PR CI에서 Backend 325건·실패/skip 0·JAR, Frontend 214건·타입/lint·기본 Turbopack 빌드·audit 취약점 0을 확인했다. 이후 사용자 승인으로 [PR #17](https://github.com/developer-sw/jeongeum-band-scheduler/pull/17)을 정식 전환·main 병합했다. HTTPS 운영 배포·실기기 수신은 후속 단계다.

병합 커밋 `f39969a45550d522916d07ab2a5d920bd2e0cf4a`의 [main CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38533584617)도 양쪽 검사에 성공했다. main job 로그에서 Backend 325건·실패/skip 0·JAR와 Frontend 214건·타입/lint·기본 Turbopack 빌드·audit 취약점 0을 확인했다. 증빙은 Git 제외 `.local/push-main17-ci-final.json`에 기록한다.

README 갱신 단계의 접속 확인에서 Docker와 앱 프로세스가 중지된 상태를 발견했다. 기존 Docker 환경을 시작하고 실행 스크립트를 숨김 백그라운드 프로세스로 재기동했다. 2026-10-14 17:23 검사에서 backend `UP`·frontend HTTP 200이며 Flyway는 기존 V10을 검증하고 `No migration necessary`로 종료했다. 추가 migration·초기화·복원은 하지 않았고 외부 sender 비활성을 유지했다. 현재 프로세스·로그·주소는 Git 제외 `.local/push-dev-runtime-live.json`, `push-dev-runtime-health.json`에 기록하며 이전 기동 시점의 LAN 주소와 구분한다. 방화벽 규칙을 새로 변경하지 않았다.

## iPhone 실수신 시험 준비 (2026-10-15)

사용자가 실제 브라우저·모바일 화면·실기기 Push 검증과 iPhone Safari 사용을 요청했다. 개발 DB와 분리한 tmpfs PostgreSQL `iphone_push_qa`, 합성 관리자/회원, 일회 VAPID 키의 실제 sender·Push worker를 준비했다. 시작 알림 worker는 이 시험에서 비활성이며, 기존 개발 DB의 외부 sender도 비활성을 유지한다. 개인키·DB 비밀번호는 해당 프로세스 환경에만 두고 로그/Git에 남기지 않는다.

별도 사용자 승인 후 시험 프런트엔드만 Cloudflare 임시 HTTPS로 연결하고 승인 이메일 한 개의 OTP로 제한했다. 익명 페이지와 API가 인증 화면으로 이동하며 시험 backend 정보를 노출하지 않는 것을 확인했다. 기존 개발 사이트·DB를 이 주소로 공개하지 않는다. 실제 이메일·시험 비밀번호·OTP·Push endpoint·임시 접속 주소는 공개 문서에 기록하지 않는다.

초기 접속은 **Cloudflare의 Email → Send login code → 메일 코드 인증**과 **정음 로그인 화면의 합성 아이디·비밀번호** 두 단계였다. Cloudflare 인증 화면에 정음 시험 계정을 입력하면 인증되지 않는다. 홈 화면 앱의 별도 저장소 때문에 이메일 인증·정음 로그인이 다시 필요할 수 있었다. 이후 접속 방식 변경 승인과 실제 수신 결과는 아래 후속 기록을 따른다.

이 준비 단계에서는 기기 설치·권한·실제 OS 수신 확인이 대기 중이었다. 브라우저 너비나 HTTP 성공, provider의 `SENT`를 실수신 완료로 간주하지 않는다. 사용자 기기 절차가 끝나면 소유 임시 터널·시험 서버·컨테이너만 종료한다. 진행 증빙과 비공개 접속 안내는 Git 제외 `.local/iphone-push-qa-*`에 보관한다.

### 후속 로그인 오류 조사와 접속 방식 변경 승인

사용자는 이메일 코드 인증 후 정음 로그인 화면까지 도착했지만 시험 계정 로그인에서 일반 연결 오류가 발생했다고 보고했다. 합성 회원으로 로컬 요청과 공개 HTTPS의 Origin/forwarded 헤더를 넣은 요청을 각각 비교한 결과 CSRF·로그인·현재 회원 조회·로그아웃 10항목이 정상 응답했다. 계정·Spring 인증 자체의 오류로 단정하지 않는다.

사용 중인 최신 `cloudflared 2026.10.0`의 이메일 제한 Quick Tunnel에서 인증 세션이 약 5초 후 만료되고 API 요청이 일반 401/인증 화면 이동으로 바뀌는 [공식 저장소 보고 #1752](https://github.com/cloudflare/cloudflared/issues/1752)를 확인했다. 현재 증상과 일치하는 유력 후보이며 실기기 요청을 직접 캡처해 확정한 결과와 구분한다. 앱의 CSRF·인증·redirect 차단을 해제하지 않는다.

사용자는 이메일 제한을 빼고 **현재의 무작위 합성 시험 계정 로그인으로 보호하는 임시 HTTPS**에서 계속하는 방식을 명시 승인했다. 이 경우 주소를 아는 사람은 로그인 화면에 접근할 수 있지만 공지·알림·회원 API는 로그인 없이는 401이며, 실제 개발 사이트·DB는 공개하지 않는다. 접속 방식만 바꾸고 기존 합성 계정·VAPID 키·시험 DB를 유지한다. 새 주소의 정상 응답과 인증 경계를 확인한 뒤 이전 이메일 인증 터널만 종료한다. 실제 기기 구독·OS 수신은 여전히 별도 확인 대상이다.

승인된 새 HTTPS 주소에서 실제 브라우저로 합성 회원 로그인 후 계정·기기 알림 설정 접근을 확인했다. 별도 HTTPS 요청 10항목으로 CSRF·로그인·현재 회원·Push 설정·로그아웃을 확인했고 12초 후에도 인증이 유지됐다. 로그아웃 후 현재 회원 조회는 401이며 익명 회원·공지·알림 API도 차단된다. TLS 인증서 검증을 유지하고 시스템 DNS/hosts는 바꾸지 않았다. 이전 이메일 OTP 터널은 종료했으며 같은 시험 DB·계정·VAPID 키를 유지한다. 이는 접속 복구 검증이며 iPhone 알림 허용·구독·실제 OS 수신 완료를 뜻하지 않는다.

### iPhone 실제 수신·클릭·로그아웃 검증 결과 (2026-10-15)

사용자가 새 HTTPS 주소에서 합성 회원 로그인·홈 화면 앱 설치·사용자 버튼으로 알림 허용을 완료했다고 확인했다. 시험 DB에서 해당 회원의 활성 기기 구독 1건을 확인한 뒤, 합성 관리자의 실제 브라우저 화면으로 중요 공지 한 건을 게시했다.

- **실제 발송**: 15:08 KST Push 작업 1건이 첫 시도 `SENT`로 완료됐으며 오류는 없었다. 알림함 기록은 합성 관리자·회원에게 각각 생성됐다.
- **실제 OS 표시·클릭**: 사용자가 iPhone 잠금 화면 수신과 알림 클릭 후 해당 시험 공지 열기를 모두 성공했다고 확인했다. provider 수락과 별도로 확보한 사용자 실기기 확인이다.
- **로그아웃 후 차단**: 사용자가 홈 화면 앱의 로그아웃 후 로그인 화면으로 돌아왔다고 확인했다. 해당 기기의 활성 구독이 1→0, 비활성 구독이 1건으로 바뀌었다. 두 번째 중요 공지는 회원의 알림함에 정상 생성됐지만 전체 Push 작업은 기존 1건 그대로여서 새 발송 작업이 생성되지 않았다. 로그아웃 후 OS 화면을 별도로 촬영한 결과로 확대하지 않는다.
- **범위·보존**: 합성 `iphone_push_qa` DB에서만 공지를 게시했다. 실제 개발 DB 39개 테이블의 건수·전체 행 지문, 시퀀스·V1~V11·관리자 1명·예약 0건·세션 14건·Docker 볼륨을 읽기 전용으로 대조하여 모두 보존됨을 확인했다. 기존 개발 서버와 비활성 외부 sender도 유지했다.

이 공지 검증 시점에는 시작 1시간 전 알림의 실제 OS 수신, 다른 물리 기기의 알림 유지, 회원 탈퇴 시 실기기 미수신, 실제 OS의 오프라인·가상 키보드·스크린리더가 미검증이었다. 시작 알림의 후속 실기기 결과는 아래 절을 따른다. 시작 알림의 제어 Clock·독립 JVM 및 탈퇴/다중 기기의 기존 PostgreSQL 검증과 이번 실기기 중요 공지 결과를 구분한다. 증빙은 Git 제외 `.local/iphone-push-qa-physical-result.json`에 저장하며 실제 계정·주소·Push endpoint·키를 공개 문서에 기록하지 않는다.

15:13 KST 정리 확인에서 소유 임시 HTTPS 터널·3006 프런트엔드·8086 백엔드를 종료하고 tmpfs 시험 PostgreSQL 컨테이너의 제거를 확인했다. 해당 포트와 55442는 더 이상 수신하지 않으며 기존 3000/8080 프로세스와 개발 PostgreSQL의 healthy 상태는 유지됐다. 실제 개발 볼륨이나 데이터는 삭제하지 않았다. 추가 코드 수정·테스트 재실행·커밋·Push·PR 전환·main 병합은 이 실기기 검증 단계에서 수행하지 않았다.

### 시작 1시간 전 알림의 iPhone 실수신 추가 검증 (2026-10-15)

사용자 요청으로 별도 tmpfs PostgreSQL `iphone_reminder_qa`, 새 합성 계정·일회 VAPID 키, 8087/3007 시험 서버와 승인된 합성 계정 인증 HTTPS를 준비했다. 원본 제품 JAR를 수정하지 않고 Git 제외 QA 클래스 경로의 `@Primary Clock`만 추가했다. 실제 `ReminderWorker`·`PushDeliveryWorker` 등록이 각각 1개이며 발송기는 대체 sender가 아닌 `VapidPushSender`임을 확인했다. 시험 Reminder poll 간격은 1초다. 실제 개발 DB·서버·시스템 시계는 건드리지 않았다.

1. iPhone 구독 전에 정상 로그인·CSRF·멱등 예약 API로 **10월 15일 17:00~18:00 개인 드럼 예약** 한 건을 생성했다. 예약 트랜잭션이 만든 `REMINDER_PLAN`의 예정 시각은 정확히 **16:00**, 상태는 `PENDING`이었다. 시작 알림·Push 작업은 0건이었다. 예약 생성 직후 Push와 시작 알림의 수신을 혼동하지 않도록 기기 등록 순서를 나눴다.
2. 사용자가 새 홈 화면 앱 로그인·알림 허용을 완료했고 활성 구독 1건을 확인했다. 진행 중인 발송·시작 알림·기존 Push 작업이 없음을 검사한 뒤 **QA Clock offset 테이블만 한 번 변경**했다. `Instant.now() + offset`은 정상 속도로 계속 흐르며 가상 15:59:30으로 이동했다. 실제 OS·PostgreSQL 시계, 예약·계획·알림·발송 작업 행을 직접 고치거나 processor를 수동 호출하지 않았다.
3. 가상 **15:59:34**에는 여전히 계획 `PENDING`·시작 알림/Push 0건이었다. 실제 `@Scheduled` 실행이 경계를 지난 뒤 **16:00:00.422**에 `START_REMINDER` 1건을 만들고 계획을 `COMPLETED`로 종료했다. 기기 발송 작업은 **16:00:01.752**, 첫 시도 `SENT`·오류 없음이었다. 이 시각은 제어된 애플리케이션 시간이며 실제 시계를 한 시간 기다린 측정과 구분한다.
4. 사용자가 iPhone **잠금 화면 알림 수신과 알림 클릭 후 해당 17:00~18:00 개인 드럼 예약 확인을 모두 성공**했다고 답했다. provider 수락과 실제 OS 확인을 별도 근거로 기록했다. 가상 **16:01:54**에 다시 조회해도 시작 알림 1건·발송 작업 1건·시도 1회 그대로여서 반복 poll에도 중복이 생성되지 않았음을 확인했다.
5. 개발 DB 39개 테이블의 건수·전체 행 지문, Flyway V1~V11·시퀀스·관리자 1명·예약 0건·세션 14건·영속 볼륨이 시험 전과 모두 같았다. 제품 코드·기존 미커밋 UI 파일·JAR는 보존했고 전체 테스트를 다시 실행하지 않았다. QA 런처는 별도 컴파일·등록/인증/API/시간 경계/실발송으로 검증했다.

실수신 증빙과 시간 경계·중복·개발 DB 대조 결과는 Git 제외 `.local/iphone-reminder-qa-physical-result.json` 및 연결된 증빙에 보관한다. 합성 계정 자격증명·임시 URL·기기 endpoint·비밀키는 공개 문서나 Git에 넣지 않는다. 단일 iPhone·개인 예약의 제어 시간 검증이며 다른 물리 기기 유지, 팀 예약 수신자의 실제 다중 기기 전달, 탈퇴·예약 변경/취소의 실기기 미수신, OS별 지연·오프라인 환경 전체까지 검증했다고 주장하지 않는다. 관련 업무 경합·취소·재시작은 기존 PostgreSQL/독립 JVM 회귀 결과와 구분한다.

15:40 KST 소유 임시 HTTPS·3007 프런트엔드·8087 백엔드·tmpfs PostgreSQL을 순서대로 종료하고 시험 컨테이너 제거와 3007/8087/55443 포트 종료를 확인했다. 종료 후에도 개발 DB 39개 테이블의 행 수·지문·세션·시퀀스·Flyway·영속 볼륨이 시험 전과 모두 같았고 기존 3000/8080 프로세스와 개발 PostgreSQL의 healthy 상태를 유지했다. 검증 결과 문서만 갱신했으며 기존 UI 수정·커밋·Push·PR 전환·main 병합은 수행하지 않았다.

### 다중 기기 실수신·기기별 로그아웃과 iPhone 클릭 복구 (2026-10-15)

사용자 요청으로 새 tmpfs `multi_device_push_qa`, 합성 계정·일회 VAPID 키, 8088/3008 시험 서버를 사용했다. 이전에 승인한 합성 계정 인증 방식의 임시 HTTPS만 공개했고 개발 사이트·DB는 연결하지 않았다. PC Chrome과 iPhone 홈 화면 앱에서 같은 시험 회원으로 각각 로그인·알림 허용을 진행했다. 활성 구독 2건·서로 다른 세션 연결 2개와 두 연결 세션의 유효 상태를 확인했다. 실제 Push sender/worker만 사용하고 예약·시작 알림·반복·교환 worker와 시스템 시간 조작은 이 시험에 사용하지 않았다.

첫 공지는 두 기기 발송 작업의 첫 시도 `SENT`만 확인했고 사용자가 한 번 더 발송을 요청했다. 별도 새 공지의 PC·iPhone 수신은 모두 성공했으나 **PC 클릭은 공지가 열리고 iPhone(iOS 26.7.1)은 마지막 알림 설정 화면에 머문다**는 보고를 받았다. 이 시점에 클릭 성공이나 로그아웃 검증 완료로 표시하지 않았다.

확인한 제품 결함은 `notificationclick`에서 기존 창의 `navigate()`/`focus()` 거부가 전체 처리를 중단해 대체 `openWindow()`를 시도하지 못하는 경로다. VM 회귀에서 수정 전 18건 중 8건 실패를 재현했다. 동일 출처 앱 창을 먼저 복원한 뒤 이동하고, 실패한 창에 매달리지 않고 다음 앱 창 또는 검증된 절대 URL의 새 창으로 복구하도록 보완했다. 클릭 시 계정 바인딩 재검사·내부 URL 허용목록·동일 출처 제한·알림함 fallback은 유지했다. [WebKit의 유사 보고](https://bugs.webkit.org/show_bug.cgi?id=263687)도 확인했으나 실제 단말의 클릭 이벤트/예외를 직접 캡처하지 않았으므로 해당 WebKit 문제와 동일 원인이라고 단정하지 않는다.

| 보완 후 검증 | 결과 |
|---|---|
| Push worker 회귀 | **18/18 통과**. 창 복원 순서, navigate 거부/null·focus 거부, 복수 창 복구·새 창 열기, 다른 출처 제외, 계정 전환·로그아웃·잘못된 URL 경계 |
| 전체 Frontend | **20파일·246건**, 타입·lint·격리 production webpack 빌드 20개 route 통과 |
| 소스·빌드 보존 | 새 격리 복사본에서 실행. 원본 162파일과 소스/public/config/package 161파일 SHA 보존·일치. 복사본 `next-env.d.ts`의 production 타입 경로 자동 변경만 별도로 확인. 기존 개발 `.next`와 실행 중인 QA `.next`는 빌드로 덮어쓰지 않음 |
| 독립 검토 | 두 수정 파일의 URL·바인딩·창 복구 경계에서 추가 확정 P1/P2 없음 |

시험 환경의 기존 정적 worker만 수정본으로 교체했고 로컬/HTTPS 응답의 바이트 일치와 `no-store`를 확인했다. 기기 구독·키·서버·주소를 유지한 채 사용자가 두 기기의 **이 기기 다시 연결**을 완료했다. 그 뒤 새 수정 확인 공지에서 **PC·iPhone 모두 수신과 클릭 후 같은 공지 열기를 성공**했다고 확인했다. 브라우저 자동 회귀 통과와 이 실기기 재확인을 구분해 기록한다.

이어 모든 이전 발송이 종료된 상태에서 사용자가 **iPhone에서만 로그아웃**했다. 정확히 한 구독이 비활성화되고 binding version이 0→1, 연결 세션은 해제됐다. 다른 구독은 활성·binding version 0·유효 로그인 세션을 유지했다. 이 행동 전후의 같은 구독 ID를 비교해 기기를 식별했으며 기기 이름이나 등록 순서로 추정하지 않았다.

로그아웃 후 새 공지는 회원 알림함 기록 1개와 **PC 발송 작업 1개만** 생성했다. PC는 첫 시도 `SENT`·오류 없음, iPhone의 해당 공지 발송 작업은 0개였다. 사용자가 **PC 수신·클릭 후 공지 열기 성공, iPhone에는 새 알림 없음**을 확인했다. 전체 시험 공지는 4건이고 회원 알림함은 공지별 1개씩 4건, 발송은 각각 2+2+2+1=7건으로 모두 첫 시도 `SENT`였다. 사용자 요청 재발송과 수정 확인을 위한 별도 공지를 같은 이벤트의 중복 발송으로 취급하지 않는다.

결과는 Git 제외 `.local/multi-device-push-qa-physical-result.json`, 기기 전후 스냅샷·매핑, 클릭 수정 서빙/전체 검증 증빙에 저장한다. 민감한 endpoint·키·세션 원문·자격증명·임시 URL은 공개 문서에 넣지 않았다. 이번 실제 기기 검증은 iPhone 단독 로그아웃 후 PC 유지 방향이며 반대 방향·다른 OS·회원 탈퇴의 전체 기기 차단은 별도다. 기존 업무·다중 기기 PostgreSQL 회귀와 이 실기기 관찰 범위를 구분한다. Backend 코드는 바꾸지 않아 전체 Backend 테스트를 다시 실행하지 않았고 기존 352건 결과를 새 실행으로 표시하지 않는다. 후속 수정은 미커밋이며 기존 PR #19 커밋의 원격 CI와 구분한다.

16:14 KST 소유 HTTPS 터널·3008 프런트엔드·8088 백엔드·tmpfs DB를 종료하고 컨테이너 제거 및 3008/8088/55444 포트 종료를 확인했다. 종료 후 개발 DB **39개 테이블의 건수·전체 행 지문**, 세션 14건·관리자 1명·예약 0건·Flyway·시퀀스·영속 볼륨이 시험 전과 모두 같았다. 기존 3000/8080 프로세스, Backend JAR와 사용자의 기존 Frontend 미커밋 4파일도 보존했다. 최종 보완은 worker·그 회귀 테스트 2파일과 관련 결과 문서이며 커밋·Push·PR 전환·main 병합은 하지 않았다.

### 탈퇴 실기기 시험 중 iPhone 클릭 문제 재발과 복원 경로 보완 (2026-10-15)

사용자 승인으로 별도 `withdrawal_device_push_qa`에서 같은 합성 회원의 인증 세션 2개·기기 구독 2개를 연결했다. 탈퇴 전 중요 공지 1건의 발송 2건은 모두 첫 시도 `SENT`였다. 사용자는 **PC·iPhone 모두 수신, PC 클릭은 공지 열기 성공, iPhone 클릭은 마지막 화면 그대로**라고 확인했다. 이전 시험의 일회 성공을 모든 실행 상태의 iPhone 클릭 해결로 확대하지 않는다. 이 환경에서 회원 탈퇴는 실행하지 않았다.

사용 한도 복구 후 시험 프로세스·임시 HTTPS와 Docker 엔진이 모두 중지된 상태를 확인했다. Docker를 복구한 뒤 기존 개발 PostgreSQL의 **39개 테이블 전체 행 지문·세션 14건·Flyway·시퀀스·영속 볼륨이 중단 전과 같음**을 읽기 전용으로 확인했다. 이전 tmpfs 시험 컨테이너는 없었고 메모리 전용 VAPID 키도 소실되어 동일 주소·구독을 복구했다고 표시하지 않는다. 새 합성 DB `withdrawal_device_push_v2_qa`와 새 계정·키·HTTPS를 사용한다. 실제 개발 3000/8080 서버는 이 복구 단계에서 시작하지 않았다.

확정된 코드의 한계는 `navigate()`가 창을 반환하고 `focus()`가 성공하면 실제 화면 도착 확인 없이 클릭 처리를 끝내며 앱 쪽 복원 경로도 없다는 점이다. **이 단말에서 클릭 이벤트가 실행됐는지, 이동 API가 어떤 값을 반환했는지는 아직 캡처하지 못했다.** [WebKit의 클릭 이벤트 미전달 보고](https://bugs.webkit.org/show_bug.cgi?id=268797)와 같은 원인이라고 단정하지 않는다.

보완은 실제 `notificationclick`에서만 허용된 내부 목적지·기기 binding·생성 시각을 IndexedDB에 기록하며 **120초 이내 한 번만** 소비한다. Push 수신만으로 이동하지 않는다. 앱 복귀·페이지 표시·화면 활성화·worker 신호에서 현재 로그인으로 기기 목록을 다시 조회하고 활성/currentDevice/id/version이 같은 경우에만 검증된 worker에서 목적지를 가져온다. 메시지 자체의 URL은 신뢰하지 않으며 이미 해당 경로에 있으면 추가 이동하지 않는다. 인증 확인 중의 임시 binding 해제만 목적지를 보존하고 로그아웃·다른 계정 연결은 삭제한다. 만료·잘못된 URL·다른 binding도 차단한다.

기존 창 이동과 새 창 fallback은 유지하고 각 native 작업은 1.5초로 제한한다. 원자적 목적지 소비·binding 조회·진단은 정지된 창 이동 뒤에서 기다리지 않는다. 앱 응답을 worker 직렬 처리 안에서 기다리지 않아 서로 대기하는 경로를 피한다. 재연결 버튼은 새 worker 업데이트·활성화를 기다린 뒤 구독을 연결한다. 알림 설정의 **알림 열기 문제 확인 → 진단 확인**은 연결 버전·최근 클릭 감지 시각·고정 상태 코드·창 수·목적지 일치 여부만 표시한다. 본문·계정·인증 세션·endpoint·키·목적지 원문은 진단에 저장하지 않는다. 인증 HTML/API 캐시나 오프라인 쓰기 재실행도 추가하지 않았다.

최종 자동 검증은 **21파일·289건**, worker 회귀 **35건**, 앱 복원 회귀 **21건** 통과다. 첫 전체 288건에서 타입·lint·격리 production webpack 빌드 20개 route를 통과했다. 추가 독립 검토에서 여러 창의 native 지연이 임시 binding 해제를 5초 제한 뒤까지 밀어낼 수 있는 P2를 발견해, 목적지 저장만 직렬 처리하고 native 작업을 분리했다. 두 창이 6초 멈춘 동안에도 임시 해제·기존 binding 복원·일회 소비가 즉시 끝나고 이후 binding이 유지되는 회귀를 추가했다. 최종 변경은 그 빌드 대비 worker와 그 테스트 2파일뿐임을 SHA로 확인하고 새 격리 복사본에서 전체 289건·worker 35건·구문 검사를 통과했다. 타입/컴파일 입력은 같아 기존 검증 빌드를 유지하고 정적 worker만 최종 SHA로 교체했다. 실제 서빙 바이트 일치와 `no-store`도 확인했다. 원본 164개 파일과 기존 사용자 UI 4파일, Backend JAR는 보존했다.

새 시험에서 사용자는 PC Chrome·iPhone 홈 화면 앱의 `push-click-recovery-v2` 버전을 확인했다. 중요 공지 1건은 회원 알림함 1개·독립 구독별 발송 2개로 생성됐고 모두 첫 시도 `SENT`·오류 없음이었다. 사용자가 **두 기기 모두 수신·클릭 후 같은 공지 열기 성공**을 확인했다. 실제 단말의 상세 클릭 진단값은 수집하지 않아 native 이동과 앱 복원 중 어느 경로가 사용됐는지 단정하지 않는다. 이 실행 조건에서의 재확인이며 모든 iOS 실행 상태의 해결을 보장하지 않는다.

모든 이전 발송이 종료되고 두 실기기 로그인이 유지된 상태에서 정상 관리자 API로 회원을 탈퇴 처리했다. 응답은 구독 해제 2개·세션 종료 3개로, 추가 세션 1개는 원래 인증 쿠키의 종료를 검사하기 위해 별도로 만든 API 검증용이다. 회원은 `WITHDRAWN`·version 0→1, 모든 회원 principal 세션은 0개, 두 구독은 비활성·binding version 0→1·연결 세션 없음이었다. 같은 원래 검증 쿠키로 회원 조회·알림함·구독 조회 각각 401, 같은 자격증명의 새 로그인도 401 `INVALID_CREDENTIALS`였다. 관리자 세션은 유지됐고 같은 키·본문의 재확인도 같은 operation·정리 건수를 반환해 기록은 1건이었다.

탈퇴 후 별도 새 중요 공지를 정상 API로 게시했다. 해당 회원의 **새 알림함·Push 작업은 모두 0건**이고 대기/처리 작업도 0개다. 기존 회원 알림 1개와 `SENT` 발송 2개, 존속 팀·관리자·Flyway V1~V11은 보존됐다. 사용자가 수동 로그아웃 없이 PC 새로고침·iPhone 앱 완전 종료/재실행으로 **두 기기 로그인 종료, 재로그인 거부, 새 공지 알림 없음**을 확인했다. 이미 provider가 수락한 이전 알림의 철회나 장시간/다른 OS의 미수신으로 확대하지 않는다. 이 실기기 환경에는 예약을 만들지 않았으며 예약 정리·미전송 outbox 취소의 기존 PostgreSQL 회귀와 구분한다.

실제 개발 DB의 39테이블 전체 행 지문·세션 14건·관리자 1명·예약 0건·시퀀스·Flyway·영속 볼륨은 V2 시험 전후 모두 같다. Git 제외 교차 검증 결과는 `.local/withdrawal-device-push-v2-qa-physical-result.json`, 개발 전후 비교와 각 단계 스냅샷에 보관한다. 기존 Backend 352건과 이전 PR CI는 이번 소스의 새 검증으로 표시하지 않으며 커밋·Push·정식 전환·main 병합은 하지 않았다.

21:03 KST 소유 V2 HTTPS 터널·프런트엔드·백엔드·tmpfs DB를 순서대로 종료했다. 프로세스 5개·3009/8089/55445 포트 종료와 자동 제거 컨테이너 부재를 확인했다. 실제 Docker 엔진·기존 PostgreSQL·영속 볼륨은 유지했고 증빙·빌드·백업은 삭제하지 않았다. 기존 개발 앱 3000/8080은 중단된 상태를 유지한다. 폐쇄 안내는 비공개 시험 계정 안내의 기존 내용 위에 기록했으며 정리 결과는 `.local/withdrawal-device-push-v2-qa-cleanup-final.json`으로 분리 보관한다.
