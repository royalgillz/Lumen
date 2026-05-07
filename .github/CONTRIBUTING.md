# Contributing to Lumen

Thank you for your interest in contributing. Please read this before opening a PR.

## Hard constraints — never violate

- **No INTERNET permission.** The app's privacy guarantee depends on this. Do not add it, even temporarily.
- **No analytics, ad, or cloud SDKs.** No Firebase, Crashlytics, Amplitude, Sentry (cloud), or any SDK that phones home.
- **No file copying.** PDFs are read in place via SAF. Never copy a PDF into the app sandbox.

## Before you start

Open an issue describing what you want to build or fix before writing code. This avoids duplicate effort and lets us agree on the approach first.

## Tech stack

The stack is locked. Do not substitute libraries without discussing in an issue first. See the README for the full list.

## Code style

- Kotlin only
- Jetpack Compose for all UI
- MVVM + Repository architecture
- Hilt for dependency injection
- Follow the existing file and package structure

## Running tests

```bash
./gradlew test          # unit tests
./gradlew lint          # lint
./gradlew connectedAndroidTest  # instrumented tests (requires device/emulator)
```

## Pull requests

- Keep PRs focused — one feature or fix per PR
- Test on a physical device, not just the emulator
- If your change touches indexing or search, test with at least 20 real PDFs
- Describe what you changed and why in the PR description

## License

By contributing you agree that your contributions will be licensed under the Apache-2.0 license.
