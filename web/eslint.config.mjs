import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
    rules: { 'vue/multi-word-component-names': 'off' },
  },
  {
    files: ['**/*.ts', '**/*.vue'],
    rules: {
      // TypeScript checks names (including DOM globals and type-only names).
      'no-undef': 'off',
      // CJK display templates intentionally use full-width spaces.
      'no-irregular-whitespace': ['error', { skipTemplates: true }],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  { files: ['tests/**/*.mjs'], languageOptions: { globals: { console: 'readonly', URL: 'readonly' } } },
)
