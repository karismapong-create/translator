# Releasing Owl Interpreter (부엉이 통역) on Google Play

App ID: `com.owlinterpreter.app` (permanent). Test builds from GitHub Actions install as
`com.owlinterpreter.app.debug`, next to the store version.

## One-time setup

1. **Upload key secrets** — GitHub repo → Settings → Secrets and variables → Actions → New repository secret:
   | Name | Value |
   | --- | --- |
   | `RELEASE_KEYSTORE_B64` | contents of `owl-upload.jks.b64` |
   | `RELEASE_STORE_PASSWORD` | the keystore password |
   | `RELEASE_KEY_PASSWORD` | the same password |
   | `RELEASE_KEY_ALIAS` | `upload` |
   Keep `owl-upload.jks` and the password somewhere safe (password manager + cloud drive).
   With Play App Signing, a lost upload key can be reset through Play Console support.
2. **Privacy policy page** — repo → Settings → Pages → Source: Deploy from a branch → `main` / `/docs`.
   URL: https://karismapong-create.github.io/translator/privacy.html
3. **Play Console** — create the app "부엉이 통역", default language Korean, free.

## Every release

1. Merge to `main`. The workflow builds `play-store-bundle` (an `.aab`) when the secrets are set.
2. Download the artifact from the Actions run and upload it to a testing track or production.

## Personal developer accounts

New personal accounts must run a closed test with at least 12 testers for 14 days before
production access. Organization accounts are exempt.

## Store graphics

`store/play-icon-512.png` (app icon) and `store/feature-graphic-1024x500.png` (feature graphic).
Phone screenshots (at least 2) must be taken on a device.
