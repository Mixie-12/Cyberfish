/// <reference types="vite/client" />

/** Set in vite.config.ts. */
interface ImportMetaEnv {
  readonly VITE_FISH_URL: string;
  readonly VITE_FISH_BYTES: number;
}

declare module '*.glsl?raw' {
  const src: string;
  export default src;
}
