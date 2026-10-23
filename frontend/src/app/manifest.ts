import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "정음",
    short_name: "정음",
    description: "JEONGEUM · SENTIMENTAL MUSIC",
    lang: "ko",
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: "#f8f4e8",
    theme_color: "#f8f4e8",
    icons: [
      { src: "/icons/jeongeum-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/jeongeum-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/jeongeum-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
