# Contributing

Bug reports, documentation improvements and focused pull requests are welcome.
For a larger change, open an issue explaining the problem and proposed approach first.

## Local development

Follow the [README](README.md#run-locally). Use synthetic data and keep credentials,
customer documents, database dumps and personal reports out of commits.

Before opening a pull request, run:

```bash
mvn -B -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run build
```

For API or workflow changes, also run `scripts/test-api.py` against a disposable
database as described in [the testing guide](docs/TESTING.md).
Add regression coverage for changes to permissions, transitions or calculations.

Keep changes focused. Describe the problem, the resulting behavior and how you
verified it. Include screenshots for visible interface changes. Follow the existing
Java and JavaScript conventions; the application interface and domain vocabulary
are currently French.

By contributing, you agree that your contributions will be licensed under the
repository's [MIT license](LICENSE).
