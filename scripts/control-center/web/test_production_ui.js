'use strict';
// Offline fixture: no browser/Vault, real tokens or file changes.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
class Element {
  constructor(tag = 'div', text = '') {this.tag = tag; this.textContent = text; this.children = []; this.events = {}; this.value = ''; this.checked = false; this.required = false; this.minLength = 0; const classes = new Set(); this.classList = {add: value => classes.add(value), contains: value => classes.has(value), toggle: (value, on) => on ? classes.add(value) : classes.delete(value)};}
  append(...nodes) {this.children.push(...nodes);}
  replaceChildren(...nodes) {this.children = nodes;}
  addEventListener(name, fn) {this.events[name] = fn;}
  querySelectorAll(tag) {return this.children.flatMap(node => [node.tag === tag ? node : null, ...node.querySelectorAll(tag)]).filter(Boolean);}
  checkValidity() {return (!this.required || this.value.length > 0) && (!this.value || this.value.length >= this.minLength);}
}
const nodes = new Map(), $ = id => {if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id);};
const documentEvents = {}, state = {profile: 'prod', category: 'Wszystkie', pendingAction: null}; let submitted;
const context = {$, state, window: {addEventListener() {}}, document: {hidden: false, addEventListener: (name, fn) => {documentEvents[name] = fn;}}, busy: () => false,
  el: (tag, text, classes = '') => {const element = new Element(tag, text); for (const name of classes.split(' ').filter(Boolean)) element.classList.add(name); return element;},
  profileChanged: () => {state.profile = $('profile').value;}, changeView: view => {state.view = view;},
  submit: (action, confirmed, extra) => {submitted = {action, confirmed, extra};}, banner: message => {throw new Error(message);},
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'production-ui.js'), 'utf8'), context);
const ui = context.window.productionUI;
ui.configure({id: 'vault-setup'});
$('confirm-check').checked = true; $('production-word').value = 'prod';
const token = $('production-fields').querySelectorAll('input')[0];
token.value = 'fixture-operator-only'; ui.update(); assert.equal($('confirm-run').disabled, true);
$('production-word').value = 'PROD'; ui.update(); assert.equal($('confirm-run').disabled, false);
assert.equal(token.type, 'password');
const payload = ui.collect({id: 'vault-setup'});
assert.equal(payload.productionConfirmation, 'PROD'); assert.equal(payload.parameters.vaultToken, 'fixture-operator-only');
ui.clear(); assert.equal(token.value, ''); assert.equal($('production-fields').children.length, 0);
ui.configure({id: 'vault-finalize-bank'});
$('confirm-check').checked = true; $('production-word').value = 'PROD'; $('production-fields').querySelectorAll('input')[0].value = 'fixture-operator-only';
ui.update(); assert.equal($('confirm-run').disabled, true);
$('production-restarted').checked = true; ui.update(); assert.equal($('confirm-run').disabled, false);
assert.equal(ui.collect({id: 'vault-finalize-bank'}).parameters.restarted, true);
$('confirm-dialog').open = true; context.document.hidden = true; documentEvents.visibilitychange();
assert.equal($('production-fields').querySelectorAll('input')[0].value, ''); assert.equal($('confirm-run').disabled, true);
context.document.hidden = false;
state.profile = 'dev'; ui.configure({id: 'artifacts-delete'});
$('confirm-check').checked = true; $('artifact-delete-word').value = ''; ui.update(); assert.equal($('confirm-run').disabled, true);
$('artifact-delete-word').value = 'USUN'; ui.update(); assert.equal($('confirm-run').disabled, false);
assert.equal(ui.collect({id: 'artifacts-delete'}).parameters.deleteConfirmation, 'USUN');
const landing = ui.landing(); landing.children.at(-1).events.click();
assert.equal(state.profile, 'prod'); assert.equal(state.category, 'Vault'); assert.equal(state.view, 'operations');
// The actual close callback: Esc/cancel must not replay a previous Run returnValue.
const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
assert.match(app, /confirm-dialog'\)\.returnValue = 'cancel'/);
const closeLine = app.split('\n').find(line => line.startsWith("$('confirm-dialog').addEventListener('close'"));
vm.runInContext(closeLine, context);
ui.configure({id: 'vault-status'}); $('confirm-check').checked = true; $('production-word').value = 'PROD';
state.pendingAction = {id: 'vault-status'}; $('confirm-dialog').returnValue = 'cancel'; $('confirm-dialog').events.close();
assert.equal(submitted, undefined);
ui.configure({id: 'vault-status'}); $('confirm-check').checked = true; $('production-word').value = 'PROD';
state.pendingAction = {id: 'vault-status'}; $('confirm-dialog').returnValue = 'run'; $('confirm-dialog').events.close();
assert.equal(submitted.extra.productionConfirmation, 'PROD'); assert.equal($('production-word').value, '');
console.log('PROD UI: typed confirmation, transient masked inputs, restart gate, delete gate, cancel/Esc and Vault landing — OK');
