// Parse documentation without editing Markdown or producing replacement diagrams.
import {JSDOM} from 'jsdom';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../..');
globalThis.window = new JSDOM('').window;
globalThis.document = window.document;
const {default: mermaid} = await import('mermaid');
mermaid.initialize({startOnLoad: false, securityLevel: 'strict'});
const deferred = JSON.parse(readFileSync(new URL('./deferred.json', import.meta.url), 'utf8'));
const files = [...new Set(execFileSync('git', ['-c', 'core.fsmonitor=false', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], {cwd: root, encoding: 'utf8'})
  .split('\0').filter(file => file.endsWith('.md') && !file.startsWith('tmp/') && !file.includes('/node_modules/')))];
let checked = 0, failures = 0, deferredCount = 0;
for (const file of files) {
  const text = readFileSync(path.join(root, file), 'utf8');
  for (const block of text.matchAll(/^```mermaid[^\r\n]*\r?\n([\s\S]*?)^```[ \t]*$/gm)) {
    const line = text.slice(0, block.index).split('\n').length;
    try {await mermaid.parse(block[1]); checked++;}
    catch (error) {
      const sha256 = createHash('sha256').update(block[1].replace(/\r\n/g, '\n')).digest('hex');
      if (error instanceof TypeError) throw error; // A broken validator must never be classified as legacy syntax.
      if (deferred.some(item => item.file === file && item.sha256 === sha256)) {
        console.warn(`${file}:${line}: unchanged historical diagram; update deferred to final documentation stage`); deferredCount++;
      } else {console.error(`${file}:${line}: invalid Mermaid syntax; sha256=${sha256}`); failures++;}
    }
  }
}
if (!checked && !failures) throw new Error('No Mermaid blocks found; check the input inventory.');
console.log(`Mermaid 11.16.1: ${checked} valid blocks; ${deferredCount} explicit historical exceptions; ${failures} failures. No documents changed.`);
process.exitCode = failures ? 1 : 0;
