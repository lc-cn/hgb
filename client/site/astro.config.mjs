// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

// The home page is "/", the docs live under "/docs"; the console is a separate app under "/console" (C42, C43).
export default defineConfig({
  server: { port: 4321, host: "127.0.0.1" },
  devToolbar: { enabled: false },
  // Only SVGs are used, so images are served as they are.
  image: { service: { entrypoint: "astro/assets/services/noop" } },
  integrations: [
    starlight({
      title: "护肝宝文档",
      logo: { light: "./src/assets/logo-light.svg", dark: "./src/assets/logo-dark.svg", replacesTitle: false },
      favicon: "/favicon.svg",
      defaultLocale: "root",
      locales: { root: { label: "简体中文", lang: "zh-CN" } },
      // No GitHub link or "edit this page" while the repository is private: visitors would get a 404.
      lastUpdated: true,
      customCss: ["./src/styles/tokens.css", "./src/styles/starlight.css"],
      components: { SiteTitle: "./src/components/DocsTitle.astro" },
      sidebar: [
        { label: "开始使用", items: [{ autogenerate: { directory: "docs/start" } }] },
        { label: "日常使用", items: [{ autogenerate: { directory: "docs/use" } }] },
        { label: "游戏", items: [{ autogenerate: { directory: "docs/games" } }] },
        { label: "开发者", items: [{ autogenerate: { directory: "docs/dev" } }] },
      ],
    }),
  ],
});
