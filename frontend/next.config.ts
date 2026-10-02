import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The Docker image (Dockerfile) builds with NEXT_OUTPUT=standalone and runs `node server.js`.
  // Local builds keep the default output, so `npm start` (next start) works as usual.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
};

export default nextConfig;
