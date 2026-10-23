import type { Metadata } from "next";
import { QueryProvider } from "@/providers/query-provider";
import { SessionProvider } from "@/features/auth/session-provider";
import { PushSessionBoundary } from "@/features/push/session-boundary";
import "./globals.css";

export const metadata: Metadata = {
  title: "정음 · JEONGEUM",
  description: "정음 동아리의 합주와 개인 연습을 함께 준비하는 공간.",
  applicationName: "정음",
  appleWebApp: { capable: true, title: "정음", statusBarStyle: "default" },
  icons: {
    icon: [
      { url: "/jeongeum-warm-logo.svg", type: "image/svg+xml" },
      { url: "/icons/jeongeum-192.png", sizes: "192x192", type: "image/png" },
    ],
    apple: [{ url: "/icons/jeongeum-apple-180.png", sizes: "180x180", type: "image/png" }],
  },
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>
        <QueryProvider><SessionProvider><PushSessionBoundary />{children}</SessionProvider></QueryProvider>
      </body>
    </html>
  );
}
