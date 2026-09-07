import js from '@eslint/js';
import globals from 'globals';

// Flat config. The goal here is to catch real defects (undefined identifiers,
// dead bindings, accidental fallthrough) without forcing a reformat of the
// existing source — Prettier owns whitespace and quoting, ESLint owns bugs.
export default [
  {
    ignores: ['dist/**', 'release/**', 'node_modules/**', 'public/assets/vendor/**', 'supabase/**']
  },

  js.configs.recommended,

  {
    // Extension source: browser page/worker context plus the chrome.* APIs.
    files: ['src/**/*.js', 'build.mjs'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.serviceworker,
        ...globals.webextensions
      }
    },
    rules: {
      // Unused *arguments* are common in chrome.* callbacks (sendResponse,
      // sender, …) so only flag unused local bindings, and let a leading
      // underscore opt out entirely.
      'no-unused-vars': [
        'error',
        {
          args: 'none',
          caughtErrors: 'none',
          varsIgnorePattern: '^_',
          ignoreRestSiblings: true
        }
      ],
      'no-undef': 'error',
      eqeqeq: ['warn', 'smart'],
      'no-var': 'error',
      'prefer-const': 'warn',
      'no-console': 'off',
      'no-empty': ['error', { allowEmptyCatch: true }]
    }
  },

  {
    // dashboard.html loads the vendored Chart.js UMD bundle via a plain <script>.
    files: ['src/dashboard/**/*.js'],
    languageOptions: {
      globals: { Chart: 'readonly' }
    }
  },

  {
    // build.mjs runs on Node, not in a page.
    files: ['build.mjs', 'zip.mjs'],
    languageOptions: {
      globals: { ...globals.node }
    }
  },

  {
    // node:test suites.
    files: ['test/**/*.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.node,
        ...globals.webextensions
      }
    },
    rules: {
      'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none', varsIgnorePattern: '^_' }],
      // Tests deliberately narrate a sequence of writes (e.g. "call A wrote its
      // checkpoint, then call B overwrote it") where the intermediate value is
      // never read back. That is the scenario, not a mistake.
      'no-useless-assignment': 'off'
    }
  }
];
