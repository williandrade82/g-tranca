import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Console de gerenciamento (Sec18.7): em producao o NGINX serve o admin-web e repassa /admin/* para o
// admin-api no mesmo origin (sem CORS, ver Main.kt do admin-api). Em desenvolvimento o proxy abaixo faz o
// mesmo papel para o `npm run dev`.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/admin': {
        target: process.env.ADMIN_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
