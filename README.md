# LegendMessage

A private messenger for Android. **End-to-end encrypted with the Signal
protocol, peer-to-peer over Tor onion services. No phone number, no email, no
account server — your identity is a cryptographic keypair and nothing else.**

> **Status: Stage 0 — build skeleton.** The app currently builds, installs, and
> shows a placeholder screen. The cryptography, pairing, Tor transport, and
> messaging are being added in stages (see the roadmap below). **Do not rely on
> this for anything sensitive yet.**

---

## The idea

Most "secure" messengers still tie you to a phone number or an email address,
and route every message through the vendor's servers. LegendMessage removes
both:

- **Identity is a keypair.** When you first open the app it generates a
  Curve25519 identity key. Your "address" is that public key plus a Tor onion
  address. There is no username registry and nothing that links you to a real
  person.
- **You add contacts by scanning a QR code** (or exchanging an invite link
  out-of-band). There is no directory to look you up in.
- **Messages go directly between devices over Tor.** Each device publishes a
  Tor **onion service**; peers connect to each other's onion address. There is
  no central server that sees metadata, holds your messages, or can be
  subpoenaed. This is the model Briar pioneered.
- **The Signal protocol does the encryption** — X3DH for the initial key
  agreement and the Double Ratchet for per-message forward secrecy, via the
  official `libsignal` library. We do not roll our own crypto.
- **Everything at rest is encrypted** — the message database with SQLCipher, and
  the keys themselves wrapped by the Android Keystore.

## Honest limitations (read this)

This is a privacy-first **learning / personal-use** project, and it is honest
about the trade-offs of the pure-P2P-over-Tor model:

- **Both devices must be online at the same time** to exchange messages
  directly. Asynchronous delivery (a "mailbox" that holds messages while a peer
  is offline) is a later-stage feature; until then, think of it like a direct
  call rather than store-and-forward email.
- **Android fights background networking.** Keeping a Tor onion service
  reachable needs a persistent **foreground service** and the user granting a
  **battery-optimization exemption**. Even then, aggressive OEM battery killers
  (common on many phones) can close the connection. This is a fundamental
  tension on mobile, not a bug we can fully fix.
- **Tor adds latency.** Connections are slower to establish than clearnet; this
  is the cost of hiding metadata.
- **No independent security audit.** For high-stakes threat models, use a mature,
  audited tool — [Briar](https://briarproject.org/),
  [SimpleX Chat](https://simplex.chat/), or
  [Molly](https://molly.im/) (a hardened Signal fork). LegendMessage is built in
  the same spirit but has none of their review behind it.

## Architecture

| Layer | Choice | Why |
|-------|--------|-----|
| Language / UI | Kotlin + Android Views | Standard, toolchain-stable |
| Encryption | `org.signal:libsignal-android` (X3DH + Double Ratchet) | The real Signal protocol, not a reimplementation |
| Identity | Curve25519 `IdentityKeyPair`, no PII | Address = identity key + onion address |
| Pairing | QR code / invite link | No directory, no server lookup |
| Transport | Tor onion service per device (Guardian Project `tor-android` + `jtorctl`) | Metadata-private P2P, the Briar model |
| Background | Foreground service + battery exemption | Keep the onion service reachable |
| Storage | SQLCipher DB + Android Keystore-wrapped keys | Encrypted at rest |

## Roadmap

- [x] **Stage 0 — build skeleton.** Kotlin project, Gradle, CI that produces an
      installable debug APK. *(you are here)*
- [ ] **Stage 1 — identity.** Generate the Curve25519 identity key on first run;
      store it under the Android Keystore; show the safety-number fingerprint and
      a QR of the public identity.
- [ ] **Stage 2 — pairing + sessions.** Scan a contact's QR, run X3DH, establish
      a Double Ratchet session (crypto only, no network yet).
- [ ] **Stage 3 — Tor transport.** Embed Tor, publish a per-device onion service,
      run it from a foreground service, dial a peer's onion.
- [ ] **Stage 4 — messaging.** Send/receive ratcheted messages over Tor, with an
      encrypted local history and an offline send queue.
- [ ] **Stage 5 — polish.** Mailbox for async delivery, backup/restore of
      identity, UX, verification flows.

## Building

CI builds a debug APK on every push (see `.github/workflows/build.yml`) and
uploads it as the `legendmessage-debug-apk` artifact.

Locally you need a JDK 17 and the Android SDK (platform 34, build-tools 34.0.0).
This repo does not commit the Gradle wrapper yet; generate it once, then build:

```sh
gradle wrapper --gradle-version 8.7   # first time only, creates ./gradlew
./gradlew assembleDebug                # -> app/build/outputs/apk/debug/app-debug.apk
```

(If you have Gradle 8.7 on your PATH you can just run `gradle assembleDebug`.)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
