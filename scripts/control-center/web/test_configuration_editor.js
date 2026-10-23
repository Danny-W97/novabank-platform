// Offline DOM fixture: no browser, network, user secrets or filesystem mutations.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Node {
  constructor(tag = 'div', text = '') {this.tag = tag; this.textContent = text; this.children = []; this.dataset = {}; this.events = {}; this.value = ''; this.checked = false; this.open = false; const classes = new Set(); this.classList = {add: x => classes.add(x), contains: x => classes.has(x), toggle: (x, enabled) => enabled ? classes.add(x) : classes.delete(x)};}
  set disabled(value) {this._disabled = Boolean(value);}
  get disabled() {return this._disabled ?? false;}
  setAttribute(name, value) {(this.attributes ??= {})[name] = String(value);}
  getAttribute(name) {return this.attributes?.[name] ?? null;}
  append(...children) {this.children.push(...children);}
  replaceChildren(...children) {this.children = children;}
  addEventListener(event, callback) {this.events[event] = callback;}
  querySelectorAll(selector) {const tags = selector.split(',').map(s => s.trim()); const found = []; for (const child of this.children) {if (child instanceof Node) {if (tags.includes(child.tag)) found.push(child); found.push(...child.querySelectorAll(selector));}} return found;}
  querySelector(selector) {return this.querySelectorAll(selector)[0];}
  showModal() {this.open = true;}
  close(value) {this.open = false; if (value) this.returnValue = value; this.events.close?.();}
}
const nodes = new Map();
const $ = id => {if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id);};
for (const id of ['file-new-key', 'file-new-value', 'file-save-check', 'file-risk-check']) nodes.set(id, new Node('input'));
nodes.set('file-save', new Node('button'));
$('file-editor-form').append($('file-entries'), $('file-new-key'), $('file-new-value'), $('file-save-check'), $('file-risk-check'), $('file-save'));
for (const id of ['file-filter-all', 'file-filter-secret', 'file-filter-setting']) {nodes.set(id, new Node('button')); $('file-editor-form').append($(id));}
const timers = new Map();
const documentEvents = {};
const state = {profile: 'test', view: 'files'};
const requests = [];
let delayReveal = false, resolveReveal;
const fileData = {profile: 'test', revision: 'a'.repeat(64), note: 'Fixture only', files: [{id: 'test', path: '.novabank/env/test.env', exists: true, size: 100, entries: [{key: 'TEST_BANK_API_KEY', value: null, secret: true, configured: true, editable: true}]}]};
const context = {
  window: {confirm: () => true, addEventListener: () => {}}, document: {hidden: false, createTextNode: text => text, addEventListener: (event, callback) => {documentEvents[event] = callback;}},
  $, state, busy: () => false, el: (tag, text = '', className = '') => {const node = new Node(tag, text); for (const name of className.split(' ').filter(Boolean)) node.classList.add(name); return node;}, pill: text => new Node('span', text),
  renderActions: () => {}, renderDocumentation: () => {}, refreshStatus: () => {}, refreshNotifications: () => {},
  setTimeout: callback => {const id = timers.size + 1; timers.set(id, callback); return id;}, clearTimeout: id => timers.delete(id),
  api: async (route, payload) => {requests.push({route, payload}); if (route.endsWith('/reveal')) return delayReveal ? new Promise(resolve => {resolveReveal = resolve;}) : {key: 'TEST_BANK_API_KEY', value: 'fixture-only-not-a-real-key'}; if (route === '/api/bootstrap') return {documentation: {}}; return structuredClone(fileData);},
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'configuration-editor.js'), 'utf8'), context);
(async () => {
  await context.window.fileEditor.load();
  const row = $('file-entries').children[0];
  const input = row.querySelector('input'), show = row.querySelector('button');
  assert.equal(row.classList.contains('file-entry'), true);
  assert.equal(row.classList.contains('secret-entry'), true);
  assert.equal(row.querySelector('label').textContent, 'TEST_BANK_API_KEY');
  assert.equal(row.querySelector('label').htmlFor, input.id);
  assert.equal(row.querySelector('span').textContent, 'Sekret');
  assert.equal(show.textContent, 'Pokaż na 45 s');
  assert.equal(input.value, '');
  assert.equal(input.type, 'password');
  assert.equal($('file-filter-all').getAttribute('aria-pressed'), 'true');
  assert.equal($('file-count-all').textContent, '1');
  assert.equal($('file-count-secret').textContent, '1');
  assert.equal($('file-count-setting').textContent, '0');
  $('file-filter-setting').events.click();
  assert.equal(row.classList.contains('hidden'), true);
  assert.equal($('file-filter-empty').classList.contains('hidden'), false);
  assert.match($('file-filter-empty').textContent, /Brak ustawień/);
  $('file-filter-secret').events.click();
  assert.equal(row.classList.contains('hidden'), false);
  assert.equal($('file-filter-empty').classList.contains('hidden'), true);
  assert.equal($('file-filter-secret').getAttribute('aria-pressed'), 'true');
  assert.equal($('file-filter-all').getAttribute('aria-pressed'), 'false');
  $('file-filter-all').events.click();
  assert.equal(requests.length, 1); // Filtering never fetches or reveals data.
  let pending = show.events.click();
  assert.equal($('file-secret-dialog').open, true);
  $('file-secret-dialog').close('cancel');
  await pending;
  assert.equal(requests.filter(r => r.route.endsWith('/reveal')).length, 0);
  pending = show.events.click();
  $('file-secret-dialog').close('reveal');
  await pending;
  assert.equal(input.type, 'text');
  assert.equal(input.value, 'fixture-only-not-a-real-key');
  assert.equal(context.window.fileEditor.dirty, false);
  for (const [id, callback] of [...timers]) {timers.delete(id); callback();}
  assert.equal(input.type, 'password');
  assert.equal(input.value, '');
  pending = show.events.click(); $('file-secret-dialog').close('reveal'); await pending;
  assert.equal(input.type, 'text');
  $('file-filter-setting').events.click();
  assert.equal(input.type, 'password');
  assert.equal(input.value, '');
  assert.equal(timers.size, 0);
  $('file-filter-all').events.click();
  pending = show.events.click(); $('file-secret-dialog').close('reveal'); await pending;
  context.document.hidden = true; documentEvents.visibilitychange();
  assert.equal(input.value, '');
  assert.equal(input.type, 'password');
  context.document.hidden = false;
  const deletion = row.querySelectorAll('input').find(field => field.type === 'checkbox');
  deletion.checked = true; deletion.events.change();
  $('file-filter-setting').events.click();
  assert.equal(row.classList.contains('marked-remove'), true);
  assert.equal(row.classList.contains('hidden'), true);
  assert.match($('file-filter-summary').textContent, /Ukryte pola z niezapisanymi zmianami: 1/);
  $('file-filter-all').events.click();
  assert.equal(deletion.checked, true);
  assert.equal(input.disabled, true);
  assert.equal(show.disabled, true);
  assert.equal(deletion.disabled, false); // Keep undo available before saving.
  deletion.checked = false; deletion.events.change();
  assert.equal(row.classList.contains('marked-remove'), false);
  assert.equal(input.disabled, false);
  assert.equal(show.disabled, false);
  input.value = 'fixture-new-test-key'; input.events.input();
  $('file-save-check').checked = true; $('file-risk-check').checked = true;
  await $('file-editor-form').events.submit({preventDefault() {}});
  const write = requests.find(r => r.route.endsWith('/save'));
  assert.equal(write.payload.profile, 'test');
  assert.equal(write.payload.values.TEST_BANK_API_KEY, 'fixture-new-test-key');
  assert.equal(write.payload.acknowledgeRisk, true);
  assert.equal(context.window.fileEditor.dirty, false);
  const freshRow = $('file-entries').children[0];
  const freshInput = freshRow.querySelector('input');
  const freshDelete = freshRow.querySelectorAll('input').find(field => field.type === 'checkbox');
  freshInput.value = 'fixture-unsaved-key'; freshInput.events.input();
  $('file-save-check').checked = true;
  $('file-filter-setting').events.click();
  assert.equal(freshInput.value, 'fixture-unsaved-key');
  assert.equal($('file-save-check').checked, true);
  assert.equal(context.window.fileEditor.dirty, true);
  $('file-filter-all').events.click();
  freshDelete.checked = true; freshDelete.events.change();
  freshDelete.checked = false; freshDelete.events.change();
  $('file-save-check').checked = true;
  await $('file-editor-form').events.submit({preventDefault() {}});
  assert.equal(requests.filter(r => r.route.endsWith('/save')).at(-1).payload.values.TEST_BANK_API_KEY, 'fixture-unsaved-key');
  fileData.files[0].entries.push(
    {key: 'TEST_REDIS_PORT', value: '6379', secret: false, configured: true, editable: true},
    {key: 'SPRING_PROFILES_ACTIVE', value: 'test', secret: false, configured: true, editable: false},
  );
  await context.window.fileEditor.load();
  const regularRow = $('file-entries').children[1], readonlyRow = $('file-entries').children[2];
  assert.equal(regularRow.classList.contains('secret-entry'), false);
  assert.equal(regularRow.querySelector('span').textContent, 'Ustawienie');
  assert.equal(regularRow.querySelector('input').value, '6379');
  assert.equal(regularRow.querySelectorAll('input').some(field => field.type === 'checkbox'), true);
  assert.equal(readonlyRow.classList.contains('readonly-entry'), true);
  assert.equal(readonlyRow.querySelector('input').disabled, true);
  assert.equal(readonlyRow.querySelectorAll('input').length, 1);
  assert.equal(readonlyRow.querySelector('small').textContent, 'Tylko odczyt · profil/system/PROD');
  assert.equal($('file-count-all').textContent, '3');
  assert.equal($('file-count-secret').textContent, '1');
  assert.equal($('file-count-setting').textContent, '2');
  $('file-filter-setting').events.click();
  assert.equal(regularRow.classList.contains('hidden'), false);
  assert.equal(readonlyRow.classList.contains('hidden'), false);
  $('file-filter-all').events.click();
  const activeSecretRow = $('file-entries').children[0];
  delayReveal = true;
  pending = activeSecretRow.querySelector('button').events.click();
  $('file-secret-dialog').close('reveal');
  await Promise.resolve();
  assert.equal(typeof resolveReveal, 'function');
  $('file-filter-setting').events.click();
  resolveReveal({key: 'TEST_BANK_API_KEY', value: 'fixture-delayed-not-a-real-key'});
  await pending;
  assert.equal(activeSecretRow.querySelector('input').type, 'password');
  assert.equal(activeSecretRow.querySelector('input').value, '');
  assert.equal(timers.size, 0); // Late response cannot unmask a hidden tile.
  delayReveal = false;
  $('file-filter-secret').events.click();
  $('file-new-key').value = 'TEST_REDIS_HOST'; $('file-new-value').value = '127.0.0.1';
  $('file-add-entry').events.click();
  const addedSettingRow = $('file-entries').children.at(-1);
  assert.equal(addedSettingRow.classList.contains('hidden'), true);
  assert.equal($('file-count-all').textContent, '4');
  assert.equal($('file-count-setting').textContent, '3');
  assert.match($('file-editor-status').textContent, /ukryty przez filtr/);
  $('file-filter-setting').events.click();
  $('file-new-key').value = 'TEST_TOTP_ENCRYPTION_KEY'; $('file-new-value').value = 'fixture-totp-only';
  $('file-add-entry').events.click();
  const addedSecretRow = $('file-entries').children.at(-1);
  assert.equal(addedSecretRow.classList.contains('secret-entry'), true);
  assert.equal(addedSecretRow.classList.contains('hidden'), true);
  assert.equal($('file-count-secret').textContent, '2');
  const regularDelete = regularRow.querySelectorAll('input').find(field => field.type === 'checkbox');
  regularDelete.checked = true; regularDelete.events.change();
  $('file-filter-secret').events.click();
  assert.equal(regularDelete.checked, true);
  $('file-save-check').checked = true;
  await $('file-editor-form').events.submit({preventDefault() {}});
  const filteredSave = requests.filter(r => r.route.endsWith('/save')).at(-1).payload;
  assert.equal(filteredSave.values.TEST_REDIS_HOST, '127.0.0.1');
  assert.equal(filteredSave.values.TEST_TOTP_ENCRYPTION_KEY, 'fixture-totp-only');
  assert.equal(Array.from(filteredSave.remove).includes('TEST_REDIS_PORT'), true);
  assert.equal($('file-filter-all').getAttribute('aria-pressed'), 'true');
  fileData.files[0].entries = []; fileData.files[0].exists = false;
  await context.window.fileEditor.load();
  assert.equal($('file-count-all').textContent, '0');
  assert.equal($('file-filter-empty').classList.contains('hidden'), false);
  assert.match($('file-filter-empty').textContent, /nie zawiera jeszcze ustawień/);
  $('file-filter-secret').events.click();
  assert.match($('file-filter-empty').textContent, /Brak sekretów/);
  $('file-new-key').value = 'TEST_BANK_API_KEY'; $('file-new-value').value = 'fixture-new-file-key';
  $('file-add-entry').events.click();
  assert.equal($('file-count-secret').textContent, '1');
  assert.equal($('file-filter-empty').classList.contains('hidden'), true);
  assert.equal($('file-entries').children[0].classList.contains('hidden'), false);
  context.window.fileEditor.reset();
  assert.equal($('file-entries').children.length, 0);
  assert.equal($('file-count-all').textContent, '0');
  assert.equal($('file-filter-empty').classList.contains('hidden'), true);
  console.log('Configuration editor: tile filters/counts/empty state, hidden edits/removals, late reveal protection, confirmation/timeout, TEST save and reset — OK');
})().catch(error => {console.error(error); process.exitCode = 1;});
