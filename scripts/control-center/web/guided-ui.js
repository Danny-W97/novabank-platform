'use strict';
window.guidedUI = (() => {
  const steps = {
    dev: [
      ['Wymagania WSL', 'Sprawdź Docker, JDK i wymagane narzędzia. Instalacje mają osobne potwierdzenie.', ['prerequisites', 'check-java', 'check-docker']],
      ['Konfiguracja DEV', 'Utwórz pliki i uzupełnij dane. Edytor nie zmienia haseł w bazie ani danych TOTP.', ['dev-env']],
      ['Infrastruktura', 'Uruchom lokalne PostgreSQL i Redis; nie usuwa wolumenów.', ['infra-up', 'infra-status']],
      ['Build i start', 'Zbuduj bank oraz sklep, następnie uruchom aplikacje. Nie zatrzymuj procesów IDE.', ['build-all', 'apps-start']],
      ['Testy i monitoring', 'Sprawdź aplikacje, E2E i opcjonalny monitoring. Każdy krok uruchamiasz osobno.', ['unit-all', 'e2e-smoke', 'monitoring-setup', 'monitoring-start']],
    ],
    test: [['Konfiguracja TEST', 'Testowe klucze są w test.env; nie uruchamiaj stałych baz DEV dla testów integracyjnych.', ['test-env']], ['Narzędzia', 'Testcontainers wymaga Docker dostępnego z WSL/JDK.', ['prerequisites', 'check-docker']], ['Weryfikacja', 'Testy jednostkowe, potem integracyjne banku i sklepu.', ['unit-all', 'it-bank', 'it-shop']]],
    prod: [
      ['Przygotowanie Vault poza panelem', 'Zainstaluj pakiet, przygotuj lokalny HTTPS/CA i zainicjalizuj Vault administracyjnie. Nie wpisuj tokenu root w panelu. Udziały Shamir trzymaj poza projektem.', ['install-vault', 'prod-secret-map', 'vault-status']],
      ['Odpieczętowanie i AppRole', 'Podawaj udziały pojedynczo. Token operatora wymaga ograniczonej polityki; setup nie tworzy biznesowych danych KV.', ['vault-unseal', 'vault-setup']],
      ['Bootstrap i Secret ID', 'Uzupełnij hasło istniejącego truststore. Rotacja testuje logowanie i zapisuje ID, bez ujawniania wartości.', ['prod-bootstrap', 'vault-rotate-all']],
      ['TLS i artefakty', 'Keystore są lokalne/self-signed. Ustaw właściwe hasła i sekrety w Vault administracyjnie; panel nie edytuje biznesowych KV.', ['prod-tls-create', 'build-prod-all', 'prod-prepare']],
      ['Restart i finalizacja', 'Zrestartuj aplikacje PROD z IDE/udokumentowanej procedury, sprawdź poprawne działanie. Dopiero potem finalizuj stare ID. Panel nie wykonuje automatycznego deploy/startu PROD.', ['vault-finalize-all']],
    ],
  };
  function render() {
    if (state.view !== 'guide') return;
    $('guide-steps').replaceChildren();
    steps[state.profile].forEach(([title, description, ids], index) => {
      const card = el('article', '', 'card documentation-card guide-step');
      card.append(el('span', `KROK ${index + 1} · ${state.profile.toUpperCase()}`, 'eyebrow'), el('h2', title), el('p', description));
      for (const id of ids) {const action = state.actions.find(item => item.id === id && available(item)); if (action) {const button = actionButton(action); button.textContent = window.panelLanguage?.action(action).title || action.title; card.append(button);}}
      $('guide-steps').append(card);
    });
  }
  $('guide-back').addEventListener('click', () => changeView('operations'));
  return {render};
})();
