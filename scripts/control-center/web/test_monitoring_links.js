'use strict';
// Offline dashboard checks: no server, credentials, monitoring starts or network requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const block = html.match(/<details id="monitoring-links"[^>]*>([\s\S]*?)<\/details>/);
assert.ok(block, 'monitoring is a native, keyboard-accessible disclosure');
assert.ok(html.indexOf('id="app-links"') < block.index, 'the tile is below the bank/shop links');
assert.match(block[0], /class="monitoring-links hidden"/);
assert.match(block[1], /<summary class="app-link">/);
assert.match(block[1], /<strong>Monitoring<\/strong>/);
assert.doesNotMatch(block[0], /\bonclick=|<iframe|<script/);
const links = [...block[1].matchAll(/<a\s+([^>]+)>/g)].map(match =>
  Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(attribute => [attribute[1], attribute[2]])));
assert.equal(links.length, 3);
for (const [index, [name, port]] of [['Prometheus', '9090'], ['Grafana', '3000'], ['Alertmanager', '9093']].entries()) {
  const link = links[index], url = new URL(link.href);
  assert.equal(url.origin, `http://localhost:${port}`);
  assert.equal(url.pathname, '/');
  assert.equal(url.username + url.password + url.search + url.hash, '');
  assert.equal(link.target, '_blank');
  assert.equal(link.rel, 'noopener noreferrer');
  assert.equal(link['aria-label'], `${name} — nowa karta`);
}

class Node {
  constructor(tag = 'div', text = '', className = '') {
    this.tag = tag; this.textContent = text; this.className = className; this.children = []; this.attributes = {}; this.open = false;
    this.classes = new Set();
    this.classList = {toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name), contains: name => this.classes.has(name)};
  }
  append(...children) {this.children.push(...children);}
  replaceChildren(...children) {this.children = children;}
  setAttribute(name, value) {this.attributes[name] = value;}
}
const nodes = new Map(), $ = id => {if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id);};
const context = {state: {profile: 'dev'}, $, date: value => value,
  el: (tag, text, className) => new Node(tag, text, className), pill: (label, status) => new Node('span', label, status),
  document: {createElementNS: (_, tag) => new Node(tag), createTextNode: text => new Node('text', text)},
  fetch() {throw new Error('Rendering links must not call or start monitoring.');},
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function renderServices('), source.indexOf('function renderJobs(')), context);
const data = {services: [{name: 'NovaBank', port: 8080, group: 'Aplikacja', up: true}, {name: 'NovaTech Shop', port: 8081, group: 'Aplikacja', up: true}], note: 'Synthetic status', checked: '2026-10-22'};
context.renderServices(data);
assert.equal($('monitoring-links').classList.contains('hidden'), false);
assert.equal($('app-links').children.length, 2, 'existing bank and shop links are retained');
const tile = $('monitoring-links'), focusedLink = new Node('a');
tile.append(focusedLink); tile.open = true;
context.renderServices(data);
assert.equal($('monitoring-links'), tile);
assert.equal(tile.open, true, '12-second status refresh must not close the chooser');
assert.equal(tile.children[0], focusedLink, 'refresh must not replace the focused panel link');
for (const profile of ['test', 'prod']) {
  context.state.profile = profile; context.renderServices({services: [], note: '', checked: ''});
  assert.equal(tile.classList.contains('hidden'), true, 'DEV dashboards must not be presented as TEST/PROD');
  assert.equal(tile.open, false);
  assert.equal($('app-links').children.length, 0);
}
context.state.profile = 'dev'; context.renderServices(data);
assert.equal(tile.classList.contains('hidden'), false);
assert.equal(tile.open, false, 'returning to DEV starts with a collapsed chooser');
const mailpitData = {
  services: [
    {name: 'NovaBank', port: 8080, group: 'Aplikacja', up: true},
    {name: 'NovaTech Shop', port: 8081, group: 'Aplikacja', up: true},
    {name: 'Mailpit · WWW', port: 18026, group: 'Infrastruktura', up: true}
  ],
  note: 'Synthetic status', checked: '2026-10-22'
};
context.renderServices(mailpitData);
assert.equal($('app-links').children.length, 3, 'mailpit link is rendered alongside bank and shop');
assert.equal($('app-links').children[2].href, 'http://localhost:18026');
assert.equal($('app-links').children[2].className, 'app-link app-link-mailpit');
console.log('Monitoring tile: placement, three local new-tab links, no auto-start, stable refresh and DEV profile guard — OK');
