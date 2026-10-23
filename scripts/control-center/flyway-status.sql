-- Read the last successfully installed version, not a lexical MAX(version).
-- Repeatable migrations have no version; failed entries cannot advance it.
SELECT current_database() AS baza,
       COUNT(*) AS migracje,
       COALESCE(MAX(installed_rank), 0) AS ostatni_rank,
       COALESCE((SELECT version
                   FROM flyway_schema_history
                  WHERE success AND version IS NOT NULL
                  ORDER BY installed_rank DESC
                  LIMIT 1), 'brak') AS ostatnia_wersja,
       COALESCE(SUM(CASE WHEN success THEN 0 ELSE 1 END), 0) AS bledne
  FROM flyway_schema_history;
