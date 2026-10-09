# Frontend

Next.js 16.4.0 · React 19.3.0 · TypeScript 6.0.3 · Tailwind 4.3.3 · TanStack Query 5.104.1. 버전은 공식 npm registry와 프레임워크 안내를 확인해 고정하고 `package-lock.json`으로 재현한다.

프로젝트 루트의 `scripts/Start-Frontend.ps1`로 실행한다. 기본 주소는 `http://127.0.0.1:3000`이며 `/api/*`를 같은 origin에서 백엔드로 전달한다. 서버 전용 `APP_API_ORIGIN`은 운영 **빌드 전에** 설정해야 한다. 브라우저 공개 변수에는 내부 API 주소나 비밀값을 넣지 않는다.

```powershell
# frontend 폴더에서 실행
npm.cmd ci
npm.cmd run typecheck
npm.cmd run lint
npm.cmd run test
npm.cmd run build
```

App Router의 로그인·초대 가입·현재 회원 홈·전체 관리자 초대 발급/목록/재발급/해지 화면을 제공한다. 제공된 정음 로고와 공통 API client·Query provider를 유지하며 예약 화면은 후속 단계다. API client는 현재 세션의 CSRF를 매 변경 요청 전에 받아 보내고 자동 재시도하지 않는다. 오프라인 변경은 즉시 거부하며 재연결 때 자동 실행하지 않는다.

`/admin/members`, `/teams`, `/teams/[id]`에서 가입 전 명단·계정 연결, 회원 권한, 복수 팀 소속·팀장과 곡 관리를 제공한다. 초대 발급은 미가입 명단을 선택하고 동명이인은 명단 번호로 구분한다. 팀 데이터 Query를 계정·역할·소속별로 나누고 권한이 바뀌면 이전 캐시를 제거한다. 사용자 수정 하단 문구 `JEONGEUM · SENTIMENTAL MUSIC`을 유지한다.

비밀번호와 초대 원문은 URL·브라우저 저장소·Query 캐시에 넣지 않는다. 로그인·발급 같은 민감 요청은 일시적인 요청으로 처리하고 화면 종료 때 관련 상태를 정리한다. 세션 상실·로그아웃은 개인 캐시를 제거하되 현재 회원 Query의 관찰자를 유지한다. 취소된 이전 요청의 401 응답이 새 로그인 세션을 지우지 않도록 응답 처리 시 AbortSignal을 확인한다.

ESLint 10.12.0·TypeScript ESLint 8.71.1·Next 공식 plugin 16.4.0·React Hooks 7.1.1을 직접 flat 설정으로 구성했다. 현재 React 일반 preset·jsx-a11y preset은 ESLint 10 peer 미지원이므로 포함하지 않는다. 의미 있는 구조·대체 텍스트·키보드 이동은 화면에서 확인하고 업무 UI 구현 때도 검증한다. [Next plugin 직접 구성](https://nextjs.org/docs/app/api-reference/config/eslint), [TypeScript ESLint](https://typescript-eslint.io/getting-started/), [React Hooks plugin](https://react.dev/reference/eslint-plugin-react-hooks)

2026-10-09 런타임 `npm audit --omit=dev`는 0건이다. 전체 감사의 high 4개 패키지는 Next 개발용 lint plugin → fast-glob → micromatch → braces에 전파된 같은 [미패치 권고](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)다. 사이트 런타임 의존성에는 포함되지 않는다. lint 설정에 임의 외부 glob 입력을 받지 않고 CI에 운영 secret을 제공하지 않는다. 지원하지 않는 강제 버전 교체는 적용하지 않았으며 upstream 패치가 나오면 의존성을 갱신하고 검증한다.

2026-10-09 재검증: 타입 생성·검사, lint 오류·경고 0, Vitest 18건, production build 통과. 실제 프록시·쿠키·CSRF 연결과 브라우저 초대·가입·로그인·권한 차단·로그아웃은 [인증 구현 안내](../docs/AUTHENTICATION.md)에 기록한다. 초기 구성의 이전 CI 결과는 [초기 구성 안내](../docs/INITIAL_SETUP.md), 인증 변경과 최종 화면 수정의 커밋별 CI는 [PR #3](https://github.com/developer-sw/jeongeum-band-scheduler/pull/3/checks)을 따른다. 프로젝트 지침은 루트 AGENTS.md를 사용하며 Next의 자동 지침 파일 생성은 해제했다.

2026-10-10 팀 관리 재검증: 타입 생성·검사, lint 오류·경고 0, Vitest 29건, production build와 런타임 의존성 감사 0건 통과. 전체 감사에는 위 개발용 lint 도구 체인의 high 4건이 그대로 남아 있다. 임시 DB의 실제 브라우저에서 명단 연결·팀장 곡 CRUD·다른 팀과 관리자 화면 권한 제한을 확인했다. 390px 모바일 팀장 화면의 가로 넘침도 없었다. 실제 단말과 운영 배포 검증은 후속 단계다. 로컬 검증 보고 시점의 원격 CI는 미실행이며 게시 후 결과는 해당 PR을 따른다.
