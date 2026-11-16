'use strict';
const docsElement = (tag, text = '', className = '') => {const node = document.createElement(tag); node.textContent = text; node.className = className; return node;};
let apiSpec = null;
function parseApiSpec(source) {
  // JSON.parse rounds int64 bounds. Preserve numeric tokens outside the safe JS
  // integer range for faithful display, without changing the served OpenAPI JSON.
  const preserved = source.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    if (/^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token))) return JSON.stringify(`__api_integer_${token}__`);
    return token;
  });
  return JSON.parse(preserved);
}
function formatApiSchema(value) {
  return JSON.stringify(value, null, 2).replace(/"__api_integer_(-?\d+)__"/g, '$1');
}
function schemaSection(title, schema) {
  const section = docsElement('section', '', 'api-schema-section'); section.append(docsElement('h4', title));
  const names = new Set();
  function refs(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref?.startsWith('#/components/schemas/')) names.add(value.$ref.split('/').pop());
    Object.values(value).forEach(refs);
  }
  refs(schema);
  const pre = docsElement('pre', formatApiSchema(schema)); pre.tabIndex = 0; section.append(pre);
  const rendered = new Set();
  while (names.size) {
    const name = names.values().next().value; names.delete(name);
    if (rendered.has(name)) continue;
    rendered.add(name); const model = apiSpec.components.schemas[name]; if (!model) continue;
    const details = docsElement('details', '', 'api-model'); details.append(docsElement('summary', `Model ${name}`));
    const code = docsElement('pre', formatApiSchema(model)); code.tabIndex = 0; details.append(code); section.append(details); refs(model);
  }
  return section;
}
function renderApiDocumentation() {
  const term = document.getElementById('api-search').value.trim().toLocaleLowerCase(window.panelLanguage?.locale || 'pl-PL');
  const container = document.getElementById('api-endpoints'); container.replaceChildren(); let count = 0;
  for (const [path, methods] of Object.entries(apiSpec.paths)) for (const [method, operation] of Object.entries(methods)) {
    if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
    if (!`${path} ${method} ${operation.summary} ${operation.description} ${operation.tags.join(' ')}`.toLocaleLowerCase(window.panelLanguage?.locale || 'pl-PL').includes(term)) continue;
    count++;
    const details = docsElement('details', '', 'api-endpoint'); const summary = docsElement('summary');
    summary.append(docsElement('span', method.toUpperCase(), `api-method ${method}`), docsElement('code', path), docsElement('span', operation.summary, 'api-summary')); details.append(summary);
    const body = docsElement('div', '', 'api-endpoint-body');
    body.append(docsElement('p', operation.description), docsElement('p', operation.security.length ? 'Zabezpieczenie: X-Control-Center-Token + zaufany Host/Origin.' : 'Zabezpieczenie: zaufany Host/Origin; token niewymagany.', 'api-security'));
    if (operation.parameters?.length) body.append(schemaSection('Parametry ścieżki / query', operation.parameters));
    if (operation.requestBody) body.append(schemaSection('Żądanie JSON (Content-Length 1–4096 bajtów)', operation.requestBody));
    body.append(schemaSection('Odpowiedzi i statusy HTTP', operation.responses)); details.append(body); container.append(details);
  }
  document.getElementById('api-docs-status').textContent = `${count} operacji HTTP · ${apiSpec.info.title} · wersja ${apiSpec.info.version}. Rozwiń wiersz, aby zobaczyć parametry i modele.`;
  if (!count) container.append(docsElement('p', 'Brak endpointów pasujących do wyszukiwania.', 'empty-state'));
}
(async () => {
  try {
    const response = await fetch('/api/openapi.json', {credentials: 'omit'});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    apiSpec = parseApiSpec(await response.text()); document.getElementById('api-security-note').textContent = apiSpec.info.description;
    document.getElementById('api-search').disabled = false;
    document.getElementById('api-search').addEventListener('input', renderApiDocumentation); renderApiDocumentation();
  } catch (error) {document.getElementById('api-docs-status').textContent = `Nie udało się odczytać specyfikacji: ${error.message}. Uruchom ponownie serwer Control Center i odśwież stronę.`;}
})();
