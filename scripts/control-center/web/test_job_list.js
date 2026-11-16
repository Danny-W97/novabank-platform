'use strict';
// Offline layout fixtures; no backend, jobs or real logs are changed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const queue = [];
let observer;
const list = {
  rows: [], visible: true, scrollTop: 0,
  classList: {toggle(key, enabled) {assert.equal(key, 'scrollable'); list.scrollable = enabled;}},
  style: {removeProperty(key) {assert.equal(key, 'max-height'); delete this.maxHeight;}},
  querySelectorAll(selector) {assert.equal(selector, '.job-item'); return this.rows;},
  getClientRects() {return this.visible ? [{}] : [];},
};
const context = {
  $: id => {assert.equal(id, 'job-list'); return list;},
  window: {requestAnimationFrame: callback => queue.push(callback)},
  ResizeObserver: class {
    constructor(callback) {this.callback = callback; this.targets = []; observer = this;}
    disconnect() {this.targets = [];}
    observe(target) {this.targets.push(target);}
  },
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function createJobListLayout('), source.indexOf('function banner(')), context);
const refresh = () => vm.runInContext('jobListLayout.refresh()', context);
const flush = () => {while (queue.length) queue.shift()();};
function rows(heights) {
  let top = 0;
  list.rows = heights.map(height => {const rect = {top, bottom: top + height}; top += height; return {getBoundingClientRect: () => rect};});
}
for (const count of [0, 1, 7, 8]) {
  rows(Array(count).fill(90)); list.style.maxHeight = '720px'; refresh(); flush();
  assert.equal(list.style.maxHeight, undefined, `${count} rows must not have a height limit`);
  assert.equal(list.scrollable, false);
}
rows(Array(9).fill(90)); refresh(); flush();
assert.equal(list.style.maxHeight, '720px', 'ninth row must require scrolling');
assert.equal(list.scrollable, true);
assert.equal(observer.targets.length, 9, 'observe the list and only its first eight rows');
rows([90, 108, 90, 108, 126, 90, 90, 108, 90, 90]); refresh(); flush();
assert.equal(list.style.maxHeight, '810px', 'wrapped titles use their actual heights');
list.scrollTop = 200; observer.callback(); observer.callback();
assert.equal(queue.length, 1, 'coalesce resize callbacks'); flush();
assert.equal(list.scrollTop, 200, 'measuring must not reset scrolling');
rows(Array(30).fill(115)); observer.callback(); flush();
assert.equal(list.style.maxHeight, '920px', 'resize/font changes update the eight-row limit');
list.visible = false; rows(Array(9).fill(0)); refresh(); flush();
assert.equal(list.style.maxHeight, '920px', 'hidden views must not collapse to zero');
list.visible = true; rows(Array(9).fill(96.125)); observer.callback(); flush();
assert.equal(list.style.maxHeight, '769px', 'showing the view measures again, preserving fractional heights');
rows(Array(8).fill(90)); refresh(); flush();
assert.equal(list.style.maxHeight, undefined, 'shrinking back to eight rows removes the limit');
assert.equal(observer.targets.length, 9, 'old row observers are replaced');
const css = fs.readFileSync(path.join(__dirname, 'styles.css'), 'utf8');
assert.match(css, /#job-list\{max-height:none;overflow-y:auto;/);
assert.match(source, /jobListLayout\.refresh\(\);\s*list\.scrollTop = scrollTop;/);
console.log('Job list: 0/8/9/30 rows, wrapped titles, resize, hidden views and preserved scroll — OK');
