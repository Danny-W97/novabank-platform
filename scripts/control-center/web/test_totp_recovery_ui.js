'use strict';
// Transient synthetic keys only. No browser storage, backend jobs or real backups.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
class Node {
  constructor(tag = 'div', text = '', className = '') {
    this.tag = tag; this.textContent = text; this.children = []; this.events = {}; this.value = ''; this.checked = false;
    const values = new Set(className.split(' ')); this.classList = {toggle: (key, on) => on ? values.add(key) : values.delete(key), contains: key => values.has(key)};
  }
  append(...items) {this.children.push(...items);}
  replaceChildren(...items) {this.children = items;}
  setAttribute() {}
  addEventListener(name, handler) {this.events[name] = handler;}
  querySelectorAll(tag) {return this.children.flatMap(child => [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]);}
  showModal() {this.open = true;}
  close() {this.open = false; this.events.close?.();}
  checkValidity() {return !this.required || /^[A-Za-z0-9+/]+={0,2}$/.test(this.value);}
}
function fixture() {
  const body = new Node(), logActions = new Node(), frames = [], posted = [], opened = [];
  const state = {view: 'tests', profile: 'dev', selectedJob: 'inspection', loading: false};
  let busyJob = false, rejectPost = false;
  const document = {body, hidden: false, events: {}, querySelector: selector => selector === '.log-actions' ? logActions : body.children.find(node => node.open),
    addEventListener(name, callback) {this.events[name] = callback;}};
  const context = {state, document, Date, el: (tag, text, style) => new Node(tag, text, style),
    busy: () => state.loading || busyJob, renderActions() {}, openJob: async job => opened.push(job),
    api: async (route, payload) => {posted.push(structuredClone(payload)); if (rejectPost) throw new Error('Synthetic expired plan'); return {id: 'verify'};},
    window: {requestAnimationFrame: callback => frames.push(callback), addEventListener() {}}};
  vm.createContext(context); vm.runInContext(fs.readFileSync(path.join(__dirname, 'totp-recovery-ui.js'), 'utf8'), context);
  const dialog = body.children[0], [title, note, fields, label, error, run, close] = dialog.children, check = label.children[0];
  const job = {id: 'inspection', action: 'backup-totp-inspect', profile: 'dev', status: 'success', finished: new Date().toISOString(),
    result: {recoveryPlan: {planId: 'a'.repeat(32), keyIds: ['legacy', 'next'], records: 2, backup: '20261019T100000Z', sha256: 'b'.repeat(64)}}};
  const flush = () => {while (frames.length) frames.shift()();};
  return {context, ui: context.window.totpRecoveryUI, dialog, note, fields, error, run, check, close, posted, opened,
    reopen: logActions.children[0], state, job, document, flush, busy(value) {busyJob = value;}, fail(value) {rejectPost = value;}};
}
(async () => {
  const f = fixture(); f.ui.observe({...f.job, status: 'running'}); f.flush(); assert.ok(!f.dialog.open);
  f.busy(true); f.ui.observe(f.job); f.flush(); assert.ok(!f.dialog.open);
  f.busy(false); f.ui.observe(f.job); f.flush(); assert.equal(f.dialog.open, true);
  const inputs = f.fields.querySelectorAll('input'); assert.equal(inputs.length, 2);
  assert.equal(inputs[0].type, 'password'); assert.equal(inputs[0].name, 'legacy'); assert.equal(inputs[1].name, 'next');
  assert.equal(f.run.disabled, true); assert.match(f.note.textContent, /nie logowania/);
  for (const input of inputs) {input.value = 'A'.repeat(43)+'='; input.events.input();}
  f.check.checked = true; f.check.events.change(); assert.equal(f.run.disabled, false);
  f.document.hidden = true; f.document.events.visibilitychange(); assert.ok(inputs.every(input => input.value === '')); assert.equal(f.run.disabled, true);
  f.document.hidden = false;
  for (const input of inputs) input.value = 'A'.repeat(43)+'=';
  f.check.checked = true; f.check.events.change(); await f.run.events.click();
  assert.equal(f.posted.length, 1); assert.equal(f.posted[0].action, 'backup-totp-verify'); assert.equal(f.posted[0].confirmed, true);
  assert.deepEqual(Object.keys(f.posted[0].parameters.keys), ['legacy', 'next']);
  assert.ok(inputs.every(input => input.value === '')); assert.equal(f.dialog.open, false); assert.equal(f.opened[0].id, 'verify');
  f.ui.observe(f.job); f.flush(); assert.equal(f.dialog.open, false, 'polling must not reopen a dismissed form');
  f.reopen.events.click(); assert.equal(f.dialog.open, true); f.close.events.click();

  const prod = fixture(); prod.state.profile = 'prod'; prod.ui.observe(prod.job); prod.flush(); assert.ok(!prod.dialog.open);
  prod.state.profile = 'dev'; prod.ui.observe(prod.job); prod.state.selectedJob = 'other'; prod.flush(); assert.ok(!prod.dialog.open);
  prod.state.selectedJob = 'inspection'; prod.ui.observe(prod.job); prod.flush();
  for (const input of prod.fields.querySelectorAll('input')) input.value = 'A'.repeat(43)+'=';
  prod.check.checked = true; prod.check.events.change(); prod.state.profile = 'prod'; await prod.run.events.click(); assert.equal(prod.posted.length, 0);

  const bad = fixture(); bad.job.result.recoveryPlan.keyIds = ['<unsafe>']; bad.ui.observe(bad.job); bad.flush(); assert.ok(!bad.dialog.open);
  const error = fixture(); error.ui.observe(error.job); error.flush(); error.fail(true);
  for (const input of error.fields.querySelectorAll('input')) input.value = 'A'.repeat(43)+'=';
  error.check.checked = true; error.check.events.change(); await error.run.events.click();
  assert.match(error.error.textContent, /expired plan/); assert.ok(error.fields.querySelectorAll('input').every(input => input.value === ''));
  assert.equal(error.opened.length, 0);
  const source = fs.readFileSync(path.join(__dirname, 'totp-recovery-ui.js'), 'utf8');
  assert.doesNotMatch(source, /localStorage|sessionStorage|console\.log/);
  console.log('TOTP recovery UI: backup-derived masked fields, confirmation/profile/busy guards, transient keys, errors and no auto-reopen — OK');
})().catch(error => {console.error(error); process.exitCode = 1;});
