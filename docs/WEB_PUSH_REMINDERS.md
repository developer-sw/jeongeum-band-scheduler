# Web Push·합주 시작 1시간 전 알림

기준일: 2026-10-14 / Asia/Seoul / 브랜치: `codex/web-push-reminders` / 시작 main: `9c05c43`. 구현과 최신 전체 회귀·격리 브라우저 검증을 완료했다. 이후 사용자 승인으로 새 V9 백업·실제 격리 복원 검증·개발 DB V10 한 건 적용·최신 8080/3000 서버 기동을 완료했다. 기존 스마트폰 LAN 설정·다이어리 UI·업무 데이터·관리자·예약·볼륨을 보존했다. 커밋·Push·main 대상 초안 PR·원격 CI 확인은 이번 승인 범위이며 정식 PR 전환·main 병합은 별도 승인 대상이다. 외부 Push 서비스와 실제 휴대폰의 수신은 아직 검증하지 않았다.

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

기존 LAN 변경은 커밋 `9977da7`로 분리했고 SW 헤더는 Push 기능 커밋에 남긴다. 새 백업·복원·실제 적용·기동·보존 증빙은 Git에서 제외한 `.local/push-v10-backup-latest.json`, `push-v10-startup.json`, `push-v10-smoke.json`, `push-v10-server.json`에 기록했다. 검증한 수정의 커밋·Push·main 대상 초안 PR·원격 CI 확인은 이번 승인 범위이며 원격 결과는 해당 PR 검사와 실행 기록을 따른다. 정식 전환·main 병합·HTTPS 운영 배포·실기기 수신은 후속 단계다.
