// This deployment's code repository, for GitHub, edit and download links: HGB_CODE_REPO if set, else the repository
// Vercel is building (so a fork's own Vercel project points at the fork), else config/instance.json.
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";

// Pages are bundled elsewhere before they render, so the file is found from the build's directory upwards.
function instanceFile() {
  for (let dir = process.cwd(); ; dir = dirname(dir)) {
    const file = join(dir, "config", "instance.json");
    if (existsSync(file)) return file;
    if (dirname(dir) === dir) throw new Error("config/instance.json not found above " + process.cwd());
  }
}
const defaults = JSON.parse(readFileSync(instanceFile(), "utf8"));
const { HGB_CODE_REPO, VERCEL_GIT_REPO_OWNER, VERCEL_GIT_REPO_SLUG } = process.env;
const vercel = VERCEL_GIT_REPO_OWNER && VERCEL_GIT_REPO_SLUG ? `${VERCEL_GIT_REPO_OWNER}/${VERCEL_GIT_REPO_SLUG}` : "";

export const codeRepo = HGB_CODE_REPO || vercel || defaults.codeRepo;
export const repoUrl = `https://github.com/${codeRepo}`;
