// Optional Node unit check; no server/browser/network and no third-party modules.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'api-docs.js'), 'utf8');
const scope = {};
vm.createContext(scope);
vm.runInContext(source.slice(0, source.indexOf('(async () =>')), scope);
for (const raw of [
  '{"maximum":9223372036854775807,"minimum":1}',
  '{"minimum":-9223372036854775808,"safe":9007199254740991}',
  '{"maximum":65535,"ratio":0.01,"note":"ID 9223372036854775807","escape":"\\\"9223372036854775807"}',
]) {
  const parsed = scope.parseApiSpec(raw);
  const rendered = scope.formatApiSchema(parsed);
  assert.deepEqual(rendered.match(/"(?:\\.|[^"\\])*"|[^\s]/g), raw.match(/"(?:\\.|[^"\\])*"|[^\s]/g));
}
const schema = scope.parseApiSpec('{"type":"integer","maximum":9223372036854775807}');
assert.match(scope.formatApiSchema(schema), /"maximum": 9223372036854775807/);
assert.doesNotMatch(scope.formatApiSchema(schema), /9223372036854776000|__api_integer_/);
console.log('API documentation: exact int64 bounds and preserved text — OK');
