# Demo signing key (NOT a production identity)

`nanogatekeeper-demo.keystore` is a committed, publicly-known demo key so that
every CI build signs with the same certificate and installs as an **update**
over the previous demo APK (ephemeral debug keys change per runner and force
an uninstall first).

- Alias `demo`, passwords `nanogatekeeper` (see `app/build.gradle.kts`).
- Provisioned once via the `provision-demo-keystore.yml` dispatch workflow.
- Never use this key for anything but the demo app.
