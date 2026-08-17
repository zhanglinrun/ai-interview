import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// 后端代理目标可配置：VITE_API_TARGET=http://localhost:18082 pnpm dev
// （默认 8082；本机同时跑 ai-group 时其 member-service 占 8082，需换端口避让）
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8082';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'ui-vendor': ['lucide-react'],
          'syntax-highlighter': ['react-syntax-highlighter'],
        },
      },
    },
  },
  server: {
      host: '0.0.0.0',
    port: 5174,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        timeout: 180_000,
        proxyTimeout: 180_000,
      },
    },
  },
});
