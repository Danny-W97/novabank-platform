// Offline fake DOM, no browser or real configuration/services.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
class Node {
  constructor(tag = 'div', text = '') {this.tag = tag; this.textContent = text; this.children = []; this.events = {}; this.checked = false; this.disabled = false; const values = new Set(); this.classList = {add: v => values.add(v), toggle: (v, on) => on ? values.add(v) : values.delete(v), contains: v => values.has(v)};}
  append(...items) {this.children.push(...items);}
  replaceChildren(...items) {this.children = items;}
  addEventListener(name, callback) {this.events[name] = callback;}
  querySelectorAll(name) {return this.children.flatMap(item => item instanceof Node ? [...(item.tag === name ? [item] : []), ...item.querySelectorAll(name)] : []);}
  close() {this.events.close?.();}
}
const nodes = new Map();
const $ = id => {if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id);};
const state = {profile: 'dev', loading: false, jobs: [], selectedJob: null, actions: [{id: 'build-bank', profiles: ['dev']}]};
const configurationState = {changes: {}};
const notificationsState = {selected: {id: 1}};
const requests = [], navigations = [], actions = [];
let uuidCounter = 0, failJob = false, deferredGet = null;
let details = {notificationId: 1, profile: 'dev', jobId: 'a'.repeat(32), context: {}, outcome: 'open', outcomeNote: null, readOnly: false,
  problem: {title: 'Fixture conflict', code: 'METRICS_CREDENTIALS_CONFLICT', impact: 'Fixture impact', document: 'docs/General_Instruction.pl.md', anchor: 'monitoring-metrics', steps: ['Read plan', 'Confirm deliberately'], check: 'metrics'},
  actions: [], plan: {revision: 'a'.repeat(64), planToken: 'b'.repeat(64), title: 'Existing DEV metrics', source: '.novabank/env/dev.env', writes: ['.novabank/monitoring/.env.monitoring', '.novabank/monitoring/prometheus.yml'], effects: 'No restarts'}, diagnosis: 'Fixture only'};
const context = {
  console, structuredClone, crypto: {randomUUID: () => `00000000-0000-4000-8000-${String(++uuidCounter).padStart(12, '0')}`},
  window: {confirm: () => true, fileEditor: {dirty: false}}, $, state, configurationState, notificationsState,
  el: (tag, text) => new Node(tag, text), busy: () => state.loading || state.jobs.some(job => job.status === 'running'),
  renderActions: () => {}, changeView: name => navigations.push(name), refreshJobs: async () => {}, refreshNotifications: async () => {},
  openJob: async (job, {navigate = true} = {}) => {state.selectedJob = job.id; if (navigate) navigations.push('tests');},
  banner: () => {}, openNotification: async () => {}, available: action => action.profiles.includes(state.profile), requestAction: action => actions.push(action.id),
  api: async (route, body) => {
    requests.push({route, body});
    if (route.startsWith('/api/support/')) return deferredGet ? deferredGet(route, body) : structuredClone(details);
    if (route === '/api/jobs') {if (failJob) throw new Error('lost HTTP response fixture'); return {id: 'c'.repeat(32), status: 'success'};}
    if (route.startsWith('/api/jobs/')) return {id: route.slice('/api/jobs/'.length), status: 'success'};
    return {};
  },
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'problem-ui.js'), 'utf8'), context);
const ui = context.window.problemUI;
(async () => {
  await ui.load({id: 1});
  assert.equal($('problem-run').disabled, true);
  assert.equal($('problem-doc-location').textContent.includes('monitoring-metrics'), true);
  assert.equal(requests.filter(r => r.route === '/api/jobs').length, 0);
  await $('problem-logs').events.click();
  assert.equal(state.selectedJob, details.jobId);
  assert.equal(navigations.at(-1), 'tests');
  await ui.load({id: 1}); // Opening logs closed/reset the notification drawer.
  $('problem-confirm').checked = true; ui.controls(); assert.equal($('problem-run').disabled, false);
  configurationState.changes = {BANK_APP_PORT: '18080'}; ui.controls(); assert.equal($('problem-run').disabled, true);
  assert.equal(configurationState.changes.BANK_APP_PORT, '18080'); configurationState.changes = {};
  state.profile = 'prod'; ui.controls(); assert.equal($('problem-run').disabled, true);
  await $('problem-run').events.click(); assert.equal(requests.filter(r => r.route === '/api/jobs').length, 0);
  state.profile = 'dev'; ui.controls(); failJob = true;
  await $('problem-run').events.click();
  const first = requests.find(r => r.route === '/api/jobs').body;
  assert.equal(first.action, 'repair-metrics-dev'); assert.equal(first.profile, 'dev'); assert.equal(first.confirmed, true);
  await ui.load({id: 1}); $('problem-confirm').checked = true; ui.controls(); await $('problem-run').events.click();
  const attempts = requests.filter(r => r.route === '/api/jobs');
  assert.equal(attempts[0].body.parameters.requestId, attempts[1].body.parameters.requestId);
  $('problem-new-attempt').events.click();
  assert.equal($('problem-confirm').checked, false);
  $('problem-confirm').checked = true; await $('problem-run').events.click();
  assert.notEqual(requests.filter(r => r.route === '/api/jobs').at(-1).body.parameters.requestId, first.parameters.requestId);
  failJob = false; $('problem-confirm').checked = true; await $('problem-run').events.click();
  assert.equal(navigations.at(-1), 'tests');
  details.plan = null; details.problem.code = 'UNCLASSIFIED_EVENT'; details.problem.check = null;
  details.actions = [{kind: 'job', id: 'shell', title: 'untrusted fixture'}, {kind: 'job', id: 'build-bank', title: 'Build bank'}];
  await ui.load({id: 1});
  assert.equal($('problem-plan').classList.contains('hidden'), true);
  assert.equal($('problem-check').classList.contains('hidden'), true);
  $('problem-actions').children[0].events.click(); assert.equal(actions.length, 0);
  $('problem-actions').children[1].events.click(); assert.equal(actions.at(-1), 'build-bank');
  details.readOnly = true; details.problem.check = 'metrics'; await ui.load({id: 1});
  assert.equal($('problem-check').classList.contains('hidden'), true);
  let resolveOld, resolveNew;
  deferredGet = route => new Promise(resolve => {if (route.endsWith('/1')) resolveOld = resolve; else resolveNew = resolve;});
  notificationsState.selected.id = 1; const old = ui.load({id: 1});
  notificationsState.selected.id = 2; const fresh = ui.load({id: 2});
  resolveNew(structuredClone(details)); await fresh; const title = $('problem-title').textContent;
  resolveOld({...structuredClone(details), problem: {...details.problem, title: 'stale response must be ignored'}}); await old;
  assert.equal($('problem-title').textContent, title);
  console.log('Problem support UI: confirmation/profile/dirty guards, no auto-run, stable retry UUID, explicit new attempt, runbooks, logs and stale response protection — OK');
})().catch(error => {console.error(error); process.exitCode = 1;});
