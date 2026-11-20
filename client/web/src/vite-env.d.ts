/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** This deployment's code repository ("owner/name"), for the feedback link; set by vite.config.ts. */
  readonly VITE_CODE_REPO: string;
  /** The API's origin when it is not this site's own /api, e.g. https://api-hgb.l2cl.link; empty for same-origin. */
  readonly VITE_API_ORIGIN?: string;
}
interface ImportMeta { readonly env: ImportMetaEnv }
