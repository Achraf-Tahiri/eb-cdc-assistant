# Security

This repository is a local demonstration prototype. Demo accounts, database
credentials and a development JWT key are deliberately public. Do not expose the
default configuration to the internet or use it for confidential documents.

Before deployment, replace the JWT secret and demo identities, restrict database
access, configure HTTPS and permitted origins, review dependencies, and add login
rate limiting and operational monitoring. The demo account seeder currently runs
at every application startup; changing a password alone is not a provisioning strategy.

See [the audit](docs/AUDIT.md) for known limitations, including interrupted AI jobs,
document editing rules and areas not covered by testing.

## Reporting a vulnerability

Use the repository's **Security → Report a vulnerability** option when available.
Do not disclose credentials, private documents or exploit details in a public issue.
For ordinary bugs without security impact, use GitHub Issues.
