import eslint from '@eslint/js'
import vue from 'eslint-plugin-vue'
import globals from 'globals'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**', '.vite-ssg-temp/**', 'design/**'] },
  eslint.configs.recommended,
  vue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  {
    rules: {
      // 官网文案较长，模板里按语义换行即可
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      // 吉祥物 SVG 和代码块需要原样输出受信任的静态内容
      'vue/no-v-html': 'off',
    },
  },
)
