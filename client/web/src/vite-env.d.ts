/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The API's origin when it is not this site's own /api, e.g. https://api-hgb.l2cl.link; empty for same-origin. */
  readonly VITE_API_ORIGIN?: string;
}
interface ImportMeta { readonly env: ImportMetaEnv }
