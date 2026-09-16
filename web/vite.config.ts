import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Proxy keeps the browser same-origin, so there is no CORS config to get wrong.
    proxy: {
      '/api': {
        target: process.env.VITE_API_BASE ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
