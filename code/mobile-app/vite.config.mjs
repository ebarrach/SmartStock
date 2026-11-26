import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  optimizeDeps: {
    entries: ['index.web.tsx'],
    exclude: [
      'react-native-safe-area-context',
      'react-native-svg',
      'lucide-react-native',
    ],
  },
  resolve: {
    alias: [{ find: /^react-native$/, replacement: 'react-native-web' }],
    extensions: [
      '.web.tsx',
      '.tsx',
      '.web.ts',
      '.ts',
      '.web.js',
      '.js',
      '.json',
    ],
  },
  define: { __DEV__: JSON.stringify(false) },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/agent': {
        target:
          process.env.SMARTSTOCK_API_PROXY_TARGET || 'http://127.0.0.1:8081',
        rewrite: p => p.replace(/^\/agent/, ''),
      },
    },
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
});
