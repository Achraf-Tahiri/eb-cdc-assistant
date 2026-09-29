# Screenshot provenance

Captured on 29 September 2026 from the actual production Next.js build connected
to the Spring Boot backend and a fresh temporary PostgreSQL database. The data
comes exclusively from `scripts/seed-demo.sql`; no private documents or accounts
appear in these images.

| File | View |
| --- | --- |
| `analytics.png` | Alerts, supplier metrics, M5 time breakdown and M6 comparison |
| `documents.png` | The three seeded demo documents |
| `document-detail.png` | Invoice requirements, seeded AI report, clarifications and audit timeline |

To reproduce, follow the README setup, load the demo seed, sign in as
`projet@demo.fr`, and open Documents, the invoice document, and Analytique.
For the M5 chart, select “Refonte du module facturation”. Images were captured in
Chromium at a 1440 × 1000 viewport, using full-page capture for the longer views.

The completeness result is seeded illustrative data. The images are not presented
as evidence of a new live AI evaluation or a benchmark. Dates and elapsed time
will vary when the seed is executed again.
