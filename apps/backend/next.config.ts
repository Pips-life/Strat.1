import type { NextConfig } from 'next';
import { withWorkflow } from 'workflow/next';

const nextConfig: NextConfig = {
  serverExternalPackages: ['metaapi.cloud-sdk'],
};

export default withWorkflow(nextConfig);
