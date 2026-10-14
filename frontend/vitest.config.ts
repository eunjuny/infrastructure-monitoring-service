import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({ plugins: [vue()], test: { include: ['tests/**/*.test.ts'], environment: 'jsdom', environmentOptions: { jsdom: { pretendToBeVisual: true } }, setupFiles: ['./tests/setup.ts'], pool: 'threads', maxWorkers: 1 } })
