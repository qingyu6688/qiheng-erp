import eslint from '@eslint/js'
import vue from 'eslint-plugin-vue'
import globals from 'globals'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'dist-demo/**', 'node_modules/**', 'coverage/**', 'test-results/**', 'playwright-report/**'] },
  eslint.configs.recommended,
  vue.configs['flat/essential'],
  vueTsConfigs.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
)
