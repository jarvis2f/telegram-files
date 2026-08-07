/**
 * Run `build` or `dev` with `SKIP_ENV_VALIDATION` to skip env validation. This is especially useful
 * for Docker builds.
 */
import "./src/env.js";

const origins = process.env.ALLOWED_DEV_ORIGINS?.split(",") || ["127.0.0.1"];

/** @type {import("next").NextConfig} */
const config = {
  output: "export",
  allowedDevOrigins: origins,
};

export default config;
