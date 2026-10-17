import type { NextConfig } from "next";

function apiOrigin() {
  const configured = process.env.APP_API_ORIGIN;
  const value = configured ?? "http://127.0.0.1:8080";
  const origin = new URL(value);

  if (
    !["http:", "https:"].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.pathname !== "/" ||
    origin.search ||
    origin.hash
  ) {
    throw new Error("APP_API_ORIGIN must be an HTTP(S) origin without credentials or a path.");
  }

  return origin.origin;
}

const nextConfig: NextConfig = {
  output: "standalone",
  agentRules: false,
  poweredByHeader: false,
  allowedDevOrigins: [
    "127.0.0.1",
    ...(process.env.APP_DEV_ORIGINS ?? "").split(",").map((host) => host.trim()).filter(Boolean),
  ],
  async headers() {
    return [{ source: "/push-sw.js", headers: [
      { key: "Cache-Control", value: "no-store" },
      { key: "X-Content-Type-Options", value: "nosniff" },
    ] }];
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiOrigin()}/api/:path*` }];
  },
};

export default nextConfig;
