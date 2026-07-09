import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import wasm from 'vite-plugin-wasm'
import topLevelAwait from 'vite-plugin-top-level-await'

// 后端代理目标可配置：VITE_API_TARGET=http://localhost:18082 pnpm dev
// （默认 8082；本机同时跑 ai-group 时其 member-service 占 8082，需换端口避让）
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8082';
const wsTarget = apiTarget.replace(/^http/, 'ws');

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    wasm(),
    topLevelAwait(),
    react(),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'ui-vendor': ['framer-motion', 'lucide-react'],
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
      },
      '/ws': {
        target: wsTarget,
        ws: true,
      },
    },
    // 忽略 @ricky0123/vad-web 的 sourcemap 警告
    sourcemapIgnoreList: (relativeSourcePath) => {
      return relativeSourcePath.includes('node_modules/.pnpm/@ricky0123+vad-web');
    },
  },
  optimizeDeps: {
    // No need to optimize vad-web since we load it via script tag
  },
});
