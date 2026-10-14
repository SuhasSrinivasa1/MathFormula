# MathFormula Android v2.4 — Stable Signing Baseline

## What changed
- Retains v2.2 Groww credential fields, Save, Refresh & Connect, encrypted credential storage and authenticated status.
- Retains v2.3 automatic NSE discovery, last scan time, scan progress, Scan Now button and Live/Closed research history.
- Creates the fixed Android application ID **`com.mathformula.alpha.stable`**. DO NOT change this identity in subsequent versions.
- First stable version code is **240**, version name **2.4.0**. Future upgrades must increment versionCode and retain the *same* signing certificate.
- This new certificate cannot upgrade the previous v1.9, v2.0, v2.1, v2.2 or v2.3 app packages. It installs separately, requiring Groww secrets to be entered once again.

## Key custody and automated release signing
**No signing private key or password is committed.** The stable PKCS12 keystore is produced and handed directly to the application owner, who must safeguard an encrypted backup and its password separately. Public signer certificate SHA-256 fingerprint:
`13:C9:31:A2:A0:B3:0C:EF:85:4E:78:CE:68:4C:1E:A6:89:1A:0E:FB:89:DE:55:52:59:A0:05:CE:8F:2A:96:44`

To enable production-signed builds automatically on GitHub Actions, repository administrators must create two **Actions Secrets** in Settings → Secrets and variables → Actions:
1. `MATHFORMULA_RELEASE_KEYSTORE_B64`: the base64-encoded bytes of the encrypted PKCS12 file; use a single line.
2. `MATHFORMULA_RELEASE_PASSWORD`: its exact keystore password.

The workflow securely writes the keystore to the ephemeral runner and builds `assembleRelease` with Gradle signing. When secrets are missing it builds only an **unsigned artifact**, never a distributable APK or a misleading release.

Use a persistent release keystore with key alias `mathformula`. Never commit or email plaintext secrets, signing keys or Groww tokens.

## Verification and rollout
- Verify certificate: `apksigner verify --verbose --print-certs MathFormula-v2.4.0-Stable.apk`.
- Confirm the signing certificate SHA-256 matches the pinned value above.
- Confirm application ID `com.mathformula.alpha.stable` and versionCode >=240.
- Validate installation + update on a real Android phone and verify Groww authentication separately.
- Production BUY recommendations are blocked unless frozen Alpha-30 evidence gates are met; Groww prices alone are research-only.
- To make the next version an in-place upgrade, keep package ID, keystore and key alias unchanged, increment versionCode.

The signing identity is distinct from the research model and does not guarantee live market-data integration or background service availability.
