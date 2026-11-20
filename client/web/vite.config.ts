import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";

// The console is its own site; it talks to the server only through /api. In development Vite forwards /api to the
// local server, so the page and the API share one origin and the session cookie works as in production, where a
// reverse proxy or CDN routes /api the same way.
const server = process.env.YYS_API ?? "http://127.0.0.1:8787";

// This deployment's code repository: HGB_CODE_REPO, else the repository Vercel is building (a fork's own project),
// else config/instance.json.
const instance = JSON.parse(readFileSync(new URL("../../config/instance.json", import.meta.url), "utf8"));
const vercelRepo = process.env.VERCEL_GIT_REPO_OWNER && process.env.VERCEL_GIT_REPO_SLUG
  ? `${process.env.VERCEL_GIT_REPO_OWNER}/${process.env.VERCEL_GIT_REPO_SLUG}` : "";
process.env.VITE_CODE_REPO = process.env.HGB_CODE_REPO || vercelRepo || instance.codeRepo;

export default defineConfig({
  plugins: [react()],
  // The site's home page and docs own "/"; the console is served under /console (C42).
  base: "/console/",
  server: { port: 5173, proxy: { "/api": { target: server, changeOrigin: false } } },
  // Built under dist/console, so a host serving dist sees the same /console/... paths the pages ask for.
  build: { outDir: "dist/console", sourcemap: true },
});
