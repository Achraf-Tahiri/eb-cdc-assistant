# Testing and reproducibility

## Fast checks

From the repository root:

```bash
mvn -B -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run build
```

The Java suite uses mocks and does not require PostgreSQL or Ollama. It covers the
workflow transition matrix, access rules, completeness checks, job handling and
ingestion replacement. The production frontend build checks compilation; it is
not an end-to-end browser suite.

## HTTP regression tests

These scripts create records and preserve audit history. Use a **new disposable
database**, with the normal backend stopped so port 8080 is free:

```bash
docker compose up -d
docker exec ebcdc-db createdb -U ebcdc ebcdc_test_local
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ebcdc_test_local \
  mvn -f backend/pom.xml spring-boot:run
```

In another terminal:

```bash
EBCDC_TEST_URL=http://localhost:8080 python3 scripts/test-api.py
EBCDC_TEST_URL=http://localhost:8080 EBCDC_TEST_DB=ebcdc_test_local \
  python3 scripts/test-analytics.py
```

The API suite checks authentication, supplier isolation, workflow progression,
clarification handling, invalid input, audit retention and concurrent requests.
The analytics script checks known-date fixtures for M1–M6 and CSV output; it
expects the Compose container name `ebcdc-db`.

To exercise local AI, start Ollama, download the models from the README, and run:

```bash
EBCDC_TEST_URL=http://localhost:8080 python3 scripts/test-rag.py
```

This needs real model inference, can take several minutes on CPU, and changes the
test corpus. It is intentionally separate from CI. Stop the test backend before
returning to your normal database. Never point these scripts at valuable data.

## Publication verification — 29 September 2026

- Java 21 / Maven: 230 tests, zero failures, errors or skipped cases.
- Node.js 24: Next.js production build passed.
- Python HTTP suite: 21 tests passed against a fresh temporary PostgreSQL database.
- Real Chromium browser: login, document list, seeded document detail, analytics
  and the M5 chart rendered with no uncaught page errors.
- Screenshots use synthetic fixtures from `scripts/seed-demo.sql`.

No new live Ollama quality evaluation was performed during repository preparation.
The [September audit](AUDIT.md) documents earlier integration and model experiments;
those historical results are not a guarantee for every environment or document.

## CI

[The GitHub Actions workflow](../.github/workflows/ci.yml) runs Java verification
and the 21 HTTP regression cases against a fresh pgvector service, plus a separate
Next.js production build. Java test reports are uploaded as workflow artifacts.
