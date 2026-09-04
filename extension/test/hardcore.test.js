import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HARDCORE_DISABLE_COOLDOWN_MS } from '../src/lib/hardcore.js';

// hardcore.js currently exports only this constant, so a trivial existence/value check is
// sufficient — nothing else in the module warrants deeper coverage.

test('HARDCORE_DISABLE_COOLDOWN_MS is a one hour cooldown', () => {
  assert.equal(HARDCORE_DISABLE_COOLDOWN_MS, 60 * 60 * 1000);
});
