'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
  constructor(tag, attrs = {}) {this.tagName = tag.toLowerCase(); this.attributes = new Map(Object.entries(attrs)); this.parentElement = null; this.value = ''; this.textContent = ''; this.listeners = {};}
  getAttribute(name) {return this.attributes.has(name) ? this.attributes.get(name) : null;}
  setAttribute(name, value) {this.attributes.set(name, String(value));}
  addEventListener(name, callback) {this.listeners[name] = callback;}
  closest(selector) {
    const selectors = selector.split(',').map(item => item.trim());
    for (let node = this; node; node = node.parentElement) {
      if (selectors.some(item => item.startsWith('#') ? node.getAttribute('id') === item.slice(1) : item === '[data-no-translate]' ? node.getAttribute('data-no-translate') !== null : item === node.tagName)) return node;
    }
    return null;
  }
}
class TextNode {
  constructor(value, parentElement) {this.nodeValue = value; this.parentElement = parentElement;}
}

const languageSelect = new Element('select', {id: 'panel-language'});
languageSelect.value = 'en';
const languageNote = new Element('p', {id: 'panel-language-note'});
const ordinary = new Element('button');
const privateRegion = new Element('div', {'data-no-translate': ''});
const nodes = [new TextNode('Konta bankowe', ordinary), new TextNode('Status usług', ordinary), new TextNode('Liczba rachunków na stronie: 5. Dane z 09:30; odświeżaj przyciskiem, bez automatycznego odpytywania.', ordinary), new TextNode('Tajne dane testowe', privateRegion)];
const attributeButton = new Element('button', {'aria-label': 'Szukaj rachunków'});
const searchInput = new Element('input', {placeholder: 'Imię, e-mail, numer lub ID…'});
searchInput.value = 'secret value remains untouched';
const dispatched = [];
const document = {
  body: {querySelectorAll: () => [attributeButton, searchInput]},
  documentElement: {lang: 'pl'},
  createTreeWalker: () => {let index = -1; return {currentNode: null, nextNode() {index++; if (index >= nodes.length) return false; this.currentNode = nodes[index]; return true;}};},
  addEventListener() {}, dispatchEvent(event) {dispatched.push(event);},
};
const elements = new Map([['panel-language', languageSelect], ['panel-language-note', languageNote]]);
class FakeMutationObserver {observe() {}}
class FakeCustomEvent {constructor(type, options) {this.type = type; this.detail = options.detail;}}
const storage = new Map([['novabank-panel-language', 'en']]);
const context = {
  window: {}, document, NodeFilter: {SHOW_TEXT: 4}, MutationObserver: FakeMutationObserver, CustomEvent: FakeCustomEvent,
  localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
  queueMicrotask, $: id => elements.get(id),
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'panel-language.js'), 'utf8'), context);

assert.equal(context.window.panelLanguage.language, 'en');
assert.equal(nodes[0].nodeValue, 'Bank accounts');
assert.equal(nodes[1].nodeValue, 'Service status');
assert.equal(nodes[2].nodeValue, 'Accounts on this page: 5. Data as of 09:30; refresh manually; automatic polling is disabled.');
assert.equal(nodes[3].nodeValue, 'Tajne dane testowe', 'marked data stays in its source language');
assert.equal(attributeButton.getAttribute('aria-label'), 'Search accounts');
assert.equal(searchInput.getAttribute('placeholder'), 'Name, email, number or ID…');
assert.equal(searchInput.value, 'secret value remains untouched', 'translation never rewrites a field value');
assert.equal(context.window.panelLanguage.action({id: 'cash-credit', title: 'Dodaj środki do konta', group: 'Salda DEV', description: 'Korekta testowego salda aktywnego konta PLN. Wybierz konto i kwotę; operacja jest audytowana.'}).title, 'Credit a DEV account');
assert.equal(context.window.panelLanguage.translate('Zatrzymać proces zadania? Wykonane zmiany nie zostaną cofnięte.'), 'Stop the task process? Completed changes will not be rolled back.');
assert.equal(context.window.panelLanguage.translate('Panele monitoringu'), 'Monitoring dashboards');
assert.equal(context.window.panelLanguage.translate('Panele muszą być uruchomione. Linki otwierają nową kartę.'), 'The dashboards must be running. Links open in a new tab.');
for (const name of ['Prometheus', 'Grafana', 'Alertmanager']) assert.equal(context.window.panelLanguage.translate(`${name} — nowa karta`), `${name} — new tab`);

languageSelect.value = 'pl';
languageSelect.listeners.change();
assert.equal(context.window.panelLanguage.language, 'pl');
assert.equal(nodes[0].nodeValue, 'Konta bankowe', 'switching languages restores the exact source label');
assert.equal(nodes[1].nodeValue, 'Status usług');
assert.equal(nodes[3].nodeValue, 'Tajne dane testowe');
assert.equal(attributeButton.getAttribute('aria-label'), 'Szukaj rachunków');
assert.equal(searchInput.getAttribute('placeholder'), 'Imię, e-mail, numer lub ID…');
assert.equal(searchInput.value, 'secret value remains untouched');
assert.equal(context.window.panelLanguage.action({id: 'cash-credit', title: 'Dodaj środki do konta', group: 'Salda DEV', description: ''}).title, 'Dodaj środki do konta');
assert.equal(dispatched.at(-1).type, 'panel-language-change');
console.log('Control Center language: static/dynamic labels, attributes, operation metadata, language switching and protected values — OK');
