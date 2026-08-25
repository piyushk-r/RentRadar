/** @type {import('next').NextConfig} */
const nextConfig = {
  // Static export: no server anywhere in this project (PRD section 16).
  output: 'export',
  trailingSlash: true,
  images: { unoptimized: true },
};

export default nextConfig;
