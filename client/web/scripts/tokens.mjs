// Writes the console's and the site's theme from design/tokens.json, the one place colours, type, spacing and breakpoints are set.
// Run by `pnpm dev` and `pnpm build`; the outputs are generated and must not be edited by hand.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = (path) => fileURLToPath(new URL(path, import.meta.url));
const tokens = JSON.parse(readFileSync(here("../../../design/tokens.json"), "utf8"));
const header = "/* Generated from design/tokens.json by scripts/tokens.mjs. Do not edit. */\n";

const scale = ["font", "text", "space", "radius", "control"]
  .flatMap((group) => Object.entries(tokens[group]).filter(([key]) => !key.startsWith("$")).map(([key, value]) => `  --${group}-${key}: ${value};`));
const colors = (mode) => Object.entries(tokens.color).map(([key, value]) => `  --color-${key}: ${value[mode]};`);
const dark = colors("dark").map((line) => `  ${line}`).join("\n");

const css = `${header}
:root {
  color-scheme: light;
${colors("light").join("\n")}
${scale.join("\n")}
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
${dark}
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
${colors("dark").join("\n")}
}
`;
writeFileSync(here("../src/theme/tokens.css"), css);
// The home page and docs (client/site) use the same variables.
writeFileSync(here("../../site/src/styles/tokens.css"), css);

const { medium, wide } = tokens.breakpoint;

// The Kotlin theme of client/shared, which the Android App and the Windows client both use (Compose).
const camel = (key) => key.replace(/-(\w)/g, (_, c) => c.toUpperCase());
const kname = (key) => /^\d/.test(key) ? (/^\d+xl$/.test(key) ? "x".repeat(Number(key[0])) + "l" : `s${key}`) : camel(key);
const px = (value) => Number(String(value).replace(/px$/, ""));
const hex = (value) => `Color(0xFF${value.slice(1).toUpperCase()})`;
const colorKeys = Object.keys(tokens.color);
const kGroup = (group, unit) => Object.entries(tokens[group]).filter(([key]) => !key.startsWith("$"))
  .map(([key, value]) => `        val ${kname(key)} = ${px(value)}.${unit}`).join("\n");
const kColors = (mode) => colorKeys.map((key) => `        ${camel(key)} = ${hex(tokens.color[key][mode])},`).join("\n");
const kotlin = `${header.replace("/*", "//").replace(" */", "")}package io.github.yysassist.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours of one theme, named as in design/tokens.json. */
data class TokenColors(
${colorKeys.map((key) => `    val ${camel(key)}: Color,`).join("\n")}
)

object DesignTokens {
    val light = TokenColors(
${kColors("light")}
    )
    val dark = TokenColors(
${kColors("dark")}
    )
    /** Window widths where the layout changes: below medium is narrow (bottom bar), from wide on the full sidebar. */
    object Breakpoint {
        val medium = ${medium}.dp
        val wide = ${wide}.dp
    }
    object Text {
${kGroup("text", "sp")}
    }
    object Space {
${kGroup("space", "dp")}
    }
    object Radius {
${kGroup("radius", "dp")}
    }
    object Control {
${kGroup("control", "dp")}
    }
}
`;
writeFileSync(here("../../shared/src/commonMain/kotlin/io/github/yysassist/shared/theme/DesignTokens.kt"), kotlin);
writeFileSync(here("../src/theme/tokens.ts"), `${header}
/** Window widths where the layout changes: below medium is narrow (bottom bar), from wide on the full sidebar. */
export const BREAKPOINT = { medium: ${medium}, wide: ${wide} } as const;
`);
