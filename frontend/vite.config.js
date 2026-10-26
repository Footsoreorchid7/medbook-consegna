import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Sviluppo: il browser parla solo con http://localhost:5173; /api è inoltrato al backend
// sul loopback IPv4. Il proxy non riscrive Origin, cookie né altri header.
export default defineConfig({
  plugins: [vue()],
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.js'],
    restoreMocks: true,
    unstubGlobals: true,
  },
})
