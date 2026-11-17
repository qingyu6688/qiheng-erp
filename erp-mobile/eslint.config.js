import pluginJs from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import { vueTsConfigs, defineConfigWithVueTs } from '@vue/eslint-config-typescript'
import globals from 'globals'

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  pluginJs.configs.recommended,
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node, uni: 'readonly' } },
  },
)
