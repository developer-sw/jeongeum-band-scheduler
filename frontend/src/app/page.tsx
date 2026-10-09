import Image from "next/image";
import Link from "next/link";

const features = [
  { number: "01", title: "함께 맞추는 시간", detail: "팀 합주와 개인 연습 일정을 한곳에서." },
  { number: "02", title: "우리 팀의 곡", detail: "함께 연주할 곡과 팀의 준비를 차곡차곡." },
  { number: "03", title: "놓치지 않는 소식", detail: "필요한 공지와 연습 알림을 가까이." },
];

export default function Home() {
  return (
    <div className="min-h-screen bg-white">
      <a
        href="#main-content"
        className="sr-only fixed left-4 top-4 z-10 rounded-lg bg-yellow px-4 py-3 font-semibold focus:not-sr-only"
      >
        본문으로 이동
      </a>

      <header className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-6 sm:px-10">
        <Link href="/" aria-label="정음 홈" className="flex items-center gap-3">
          <Image src="/jeongeum-logo.jpg" alt="" width={60} height={60} />
          <span className="text-lg font-extrabold tracking-[0.18em]">JEONGEUM</span>
        </Link>
        <span className="rounded-full border border-navy/10 px-4 py-2 text-xs font-semibold text-navy-soft">
          정음 동아리
        </span>
      </header>

      <main id="main-content" className="mx-auto max-w-6xl px-6 pb-14 sm:px-10 sm:pb-20">
        <section
          aria-labelledby="welcome-title"
          className="grid items-center gap-8 rounded-3xl bg-paper p-7 sm:p-12 lg:grid-cols-[1.25fr_1fr] lg:p-16"
        >
          <div>
            <p className="mb-7 flex items-center gap-2 text-sm font-semibold text-navy-soft">
              <span aria-hidden="true" className="h-2 w-2 rounded-full bg-yellow" />
              새로운 합주 공간을 준비하고 있어요
            </p>
            <h1 id="welcome-title" className="text-4xl leading-tight font-extrabold tracking-tight sm:text-5xl lg:text-6xl">
              우리의 합주가
              <br />
              시작되는 곳.
            </h1>
            <div aria-hidden="true" className="mt-7 h-1.5 w-16 rounded-full bg-yellow" />
            <p className="mt-7 max-w-md text-base leading-8 text-navy-soft sm:text-lg">
              함께 연주하고, 각자의 소리를 더하고.
              <br />
              정음의 다음 연습을 여기서 만나세요.
            </p>
            <p className="mt-9 inline-block rounded-xl bg-white px-5 py-4 text-sm leading-6 text-navy-soft">
              서비스가 열리면 관리자의 개인 초대로 시작할 수 있어요.
            </p>
          </div>
          <div className="mx-auto w-full max-w-72 rounded-3xl bg-white p-3 sm:max-w-80 lg:max-w-96">
            <Image
              src="/jeongeum-logo.jpg"
              alt="남색과 노란색의 정음 로고"
              width={1254}
              height={1254}
              priority
              sizes="(max-width: 640px) 288px, (max-width: 1024px) 320px, 384px"
              className="h-auto w-full rounded-2xl"
            />
          </div>
        </section>

        <section aria-labelledby="features-title" className="mt-12 sm:mt-16">
          <h2 id="features-title" className="text-sm font-bold tracking-[0.14em] text-navy-soft">
            정음의 연습, 더 가깝게
          </h2>
          <ul className="mt-6 grid gap-7 sm:grid-cols-3 sm:gap-8">
            {features.map((feature) => (
              <li key={feature.number} className="border-t border-navy/15 pt-5">
                <span aria-hidden="true" className="text-xs font-bold tracking-widest text-navy-soft">
                  {feature.number}
                </span>
                <h3 className="mt-3 text-lg font-bold">{feature.title}</h3>
                <p className="mt-2 text-sm leading-7 text-navy-soft">{feature.detail}</p>
              </li>
            ))}
          </ul>
        </section>
      </main>

      <footer className="border-t border-navy/10 px-6 py-7 sm:px-10">
        <p className="mx-auto max-w-6xl text-xs font-medium tracking-[0.15em] text-navy-soft">
          JEONGEUM · PLAY TOGETHER
        </p>
      </footer>
    </div>
  );
}
