'use strict';
// Offline DOM/transport fixtures: no operations, real logs or environment changes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
class Node {
  constructor(tag = 'div', text = '', className = '') {
    this.tag = tag; this.textContent = text; this.className = className; this.children = []; this.attributes = {};
    this.events = {}; this.scrollTop = 0; this.scrollHeight = 200; this.clientHeight = 100; this.scrolls = 0;
    const classes = new Set(className.split(' ').filter(Boolean));
    this.classList = {add: value => classes.add(value), contains: value => classes.has(value),
      toggle: (value, enabled) => enabled ? classes.add(value) : classes.delete(value)};
  }
  append(...children) {this.children.push(...children);}
  replaceChildren(...children) {this.children = children;}
  addEventListener(name, handler) {this.events[name] = handler;}
  setAttribute(name, value) {this.attributes[name] = value;}
  focus() {this.focused = true;}
  closest() {return this.container;}
  scrollIntoView() {this.scrolls++;}
}
const job = (id, status = 'success', log = `${id} output`) => ({id, title: `Task ${id}`, action: 'unit-bank',
  profile: 'test', status, started: '2026-10-19T09:00:00Z', exitCode: status === 'running' ? null : 0, log});
const summary = ({log, ...value}) => value;
const deferred = () => {let resolve, reject; const promise = new Promise((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject};};
const tick = async () => {for (let i = 0; i < 15; i++) await Promise.resolve();};
function fixture(initial = []) {
  const nodes = new Map(), frames = [], banners = [], requests = [];
  const $ = id => {if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id);};
  const panel = new Node(); $('log-title').container = panel;
  const state = {profile: 'test', view: 'tests', token: 'synthetic-csrf', jobs: initial.map(summary),
    selectedJob: initial[0]?.id || null, loading: false, actions: []};
  const transport = {list: initial.map(summary), details: new Map(initial.map(value => [value.id, value])),
    listHandler: null, detailHandler: null, posted: null, postError: null};
  const context = {state, $, statuses: {running: 'W toku', success: 'Zakończono'},
    el: (tag, text, className) => new Node(tag, text, className), date: value => value,
    localizedJobTitle: job => job.title,
    pill: (text, status) => new Node('span', text, status), jobListLayout: {refresh() {}},
    renderActions() {}, busy: () => state.loading, banner: message => banners.push(message),
    document: {querySelector: () => new Node()},
    window: {panelLanguage: {action: action => action}, requestAnimationFrame: callback => frames.push(callback)},
    changeView: view => {state.view = view;},
    api: async (route, payload) => {
      requests.push({route, payload});
      if (payload) {if (transport.postError) throw transport.postError; return transport.posted;}
      if (route === '/api/jobs') return transport.listHandler ? transport.listHandler() : {jobs: transport.list};
      const id = route.slice('/api/jobs/'.length);
      return transport.detailHandler ? transport.detailHandler(id) : transport.details.get(id);
    },
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('function renderJobs()'), source.indexOf('let statusRefreshing')), context);
  vm.runInContext(source.slice(source.indexOf('async function submit('), source.indexOf('function operationCard(')), context);
  const run = expression => vm.runInContext(expression, context);
  run('renderJobs()');
  const flushFrames = () => {while (frames.length) frames.shift()();};
  return {state, transport, nodes, $, panel, banners, requests, run, flushFrames, context};
}
(async () => {
  // Regression: a short job already finished before POST returns; GET is identical to local state.
  const f = fixture([job('old')]);
  f.state.view = 'operations';
  f.context.changeView = view => {f.state.view = view; void f.context.refreshJobs();};
  f.$('log-output').textContent = 'old private log'; f.$('job-list').scrollTop = 300;
  const fresh = job('new'); f.transport.posted = summary(fresh);
  f.transport.list = [summary(fresh), ...f.transport.list]; f.transport.details.set('new', fresh);
  await f.run("submit({id:'unit-bank'}, true)"); f.flushFrames();
  assert.equal(f.state.view, 'tests'); assert.equal(f.state.selectedJob, 'new');
  assert.equal(f.$('job-count').textContent, '2');
  assert.equal(f.$('job-list').children[0].attributes['aria-pressed'], 'true');
  assert.equal(f.$('log-title').textContent, 'Task new'); assert.equal(f.$('log-output').textContent, 'new output');
  assert.equal(f.$('job-list').scrollTop, 0); assert.equal(f.$('log-title').focused, true); assert.equal(f.panel.scrolls, 1);
  // Retrying/opening the same response does not duplicate rows.
  await f.context.openJob(summary(fresh)); assert.equal(f.state.jobs.length, 2);

  // Show a pending header immediately, never the previous task's log while loading.
  const loading = fixture([job('old')]), detail = deferred();
  loading.transport.details.set('new', fresh); loading.transport.detailHandler = () => detail.promise;
  const opening = loading.context.openJob(summary(fresh));
  assert.equal(loading.$('log-title').textContent, 'Task new');
  assert.equal(loading.$('log-output').textContent, 'Oczekiwanie na wynik…');
  assert.equal(loading.$('cancel-job').classList.contains('hidden'), true);
  loading.transport.list = [summary(fresh)]; detail.resolve(fresh); await opening;

  // A GET begun before POST must not erase the newly opened task. Queue a fresh GET.
  const stale = fixture([job('old')]), oldList = deferred();
  let listReads = 0;
  stale.transport.listHandler = () => ++listReads === 1 ? oldList.promise : {jobs: [summary(fresh), summary(job('old'))]};
  stale.transport.details.set('new', fresh);
  const polling = stale.context.refreshJobs(); await stale.context.openJob(summary(fresh));
  oldList.resolve({jobs: [summary(job('old'))]}); await polling; await tick();
  assert.ok(listReads >= 2); assert.equal(stale.state.jobs[0].id, 'new');
  assert.equal(stale.$('job-list').children[0].attributes['aria-pressed'], 'true');
  assert.equal(stale.$('log-output').textContent, 'new output');

  // Newer requests and task switches win over late log data AND late errors.
  const logs = fixture([job('old'), fresh]), first = deferred(), second = deferred(); let reads = 0;
  logs.transport.detailHandler = () => ++reads === 1 ? first.promise : second.promise;
  const before = logs.context.refreshLog(), after = logs.context.refreshLog();
  second.resolve(job('old', 'success', 'latest data')); await after;
  first.resolve(job('old', 'running', 'stale data')); await before;
  assert.equal(logs.$('log-output').textContent, 'latest data');
  const failed = deferred(); logs.transport.detailHandler = id => id === 'old' ? failed.promise : fresh;
  const obsolete = logs.context.refreshLog(); await logs.context.selectJob('new');
  failed.reject(new Error('obsolete error')); await obsolete;
  assert.equal(logs.$('log-output').textContent, 'new output'); assert.equal(logs.banners.length, 0);

  // Polling updates status/logs even with unchanged summary, without navigation/focus stealing.
  const live = fixture([job('live', 'running', 'line one')]);
  await live.context.refreshJobs(); live.$('log-output').scrollTop = 0;
  live.$('log-output').scrollHeight = 1000;
  live.transport.details.set('live', job('live', 'running', 'line one\nline two'));
  await live.context.refreshJobs();
  assert.equal(live.$('log-output').textContent, 'line one\nline two');
  assert.equal(live.$('log-output').scrollTop, 0, 'do not drag an operator reading old lines to the bottom');
  live.transport.list = [summary(job('live'))]; live.transport.details.set('live', job('live'));
  await live.context.refreshJobs(); assert.match(live.$('log-meta').textContent, /Zakończono/);
  assert.equal(live.panel.scrolls, 0, 'polling must not navigate or move the page');
  assert.equal(live.$('job-list').children[0].attributes['aria-pressed'], 'true');
  live.state.view = 'dashboard'; await live.context.refreshJobs(); assert.equal(live.state.view, 'dashboard');

  // A user deliberately changing task/view wins over deferred scrolling or a stale repair completion.
  const operator = fixture([job('old'), fresh]);
  await operator.context.openJob(summary(fresh)); await operator.context.selectJob('old'); operator.flushFrames();
  assert.equal(operator.state.selectedJob, 'old'); assert.equal(operator.panel.scrolls, 0);
  operator.state.view = 'dashboard';
  await operator.context.openJob(summary(fresh), {navigate: false}); operator.flushFrames();
  assert.equal(operator.state.selectedJob, 'old'); assert.equal(operator.state.view, 'dashboard');

  const failure = fixture([job('old')]); failure.transport.postError = new Error('synthetic refusal');
  await failure.run("submit({id:'unit-bank'}, true)");
  assert.equal(failure.state.selectedJob, 'old'); assert.equal(failure.state.jobs.length, 1);
  assert.deepEqual(failure.banners, ['synthetic refusal']);
  assert.match(source, /setInterval\(refreshJobs, 2500\)/);
  assert.match(source, /\$\('cash-dialog'\)\.close\(\); await openJob\(job\)/);
  console.log('Job navigation: immediate POST rendering, pending logs, stale GET/log protection, live polling and preserved operator view — OK');
})().catch(error => {console.error(error); process.exitCode = 1;});
