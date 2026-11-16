let shopAccountCsrfPromise = null;

function shopAccountCsrfToken() {
  const cookie = document.cookie.split('; ').find(value => value.startsWith('SHOP-XSRF-TOKEN='));
  return cookie ? decodeURIComponent(cookie.substring('SHOP-XSRF-TOKEN='.length)) : '';
}

async function shopAccountEnsureCsrf() {
  if (shopAccountCsrfToken()) return;
  shopAccountCsrfPromise ||= fetch('/api/auth/csrf', { credentials: 'same-origin' });
  const response = await shopAccountCsrfPromise;
  if (!response.ok) throw new Error('Nie można przygotować ochrony formularza.');
}

async function shopAccountFetch(url, options = {}) {
  const request = { ...options, credentials: 'same-origin' };
  const headers = new Headers(options.headers || {});
  const method = (options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    await shopAccountEnsureCsrf();
    headers.set('X-XSRF-TOKEN', shopAccountCsrfToken());
  }
  request.headers = headers;
  return fetch(url, request);
}

function shopAccountRequireLogin() {
  const returnUrl = window.location.pathname + window.location.search;
  window.location.href = '/shop-login.html?return=' + encodeURIComponent(returnUrl);
}

async function shopAccountReadError(response) {
  const body = await response.clone().text();
  try {
    const data = JSON.parse(body);
    return data.detail || data.message || data.title || body;
  } catch (ignored) {
    return body || 'Nie udało się zapisać zmian.';
  }
}

function shopAccountMessage(element, text, type) {
  element.textContent = text;
  element.className = 'form-message ' + (type || '');
}

function shopAccountPrice(value, currency) {
  return new Intl.NumberFormat('pl-PL', { style: 'currency', currency: currency || 'PLN' }).format(value);
}
