# LegendMessage

A private messenger for Android. **End-to-end encrypted with the Signal
protocol, peer-to-peer over Tor onion services. No phone number, no email, no
account server — your identity is a cryptographic keypair and nothing else.**

> **Status: feature-complete through Stage 5 (building green).** Identity,
> QR pairing, embedded Tor + onion services, messaging over Tor with encrypted
> history and an offline queue, safety-number verification, and encrypted
> backup/restore are all implemented. The code is verified to **compile and
> package into an installable APK**; it has **not** been through on-device
> interop testing or a security audit, so **do not rely on it for anything
> sensitive yet** (see "Honest limitations").

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

## How it works, end to end

1. **First run** generates a Curve25519 identity key (no PII, no registration),
   wrapped by the Android Keystore. You pick a display name — a local label only.
2. **Tor comes up** in a foreground service and publishes your onion v3 service
   (key persisted, so the address is stable). Your onion forwards to a local
   port where the app listens.
3. **Pairing:** you show your QR (identity + signed prekey + Kyber prekey +
   onion address); your contact scans it, and you scan theirs. Each scan saves
   the other's card — no server, no directory.
4. **Sending:** the first message you send runs X3DH + PQXDH against the stored
   card to open a Double Ratchet session, encrypts, and dials the peer's onion
   over Tor's SOCKS proxy. The ciphertext is stored and marked pending until
   delivered; if the peer is offline it stays queued and retries.
5. **Receiving:** the peer server reads the envelope, picks the session by the
   sender's identity key, decrypts, and stores the plaintext in the SQLCipher DB.
6. **Verify:** tap a conversation's title to see the pair's safety number and
   compare it out of band — that rules out a machine-in-the-middle.
7. **Backup:** Settings exports your identity + contacts as a passphrase-
   encrypted file you can restore on another device.

## Roadmap

- [x] **Stage 0 — build skeleton.** Kotlin project, Gradle, CI producing an APK.
- [x] **Stage 1 — identity.** Curve25519 identity on first run, Keystore-wrapped,
      with fingerprint + identity QR.
- [x] **Stage 2 — pairing + sessions.** Full prekey bundle (incl. Kyber), QR
      pairing, X3DH/PQXDH session establishment, contacts.
- [x] **Stage 3 — Tor transport.** Embedded Tor, per-device onion service from a
      foreground service, SOCKS dialing.
- [x] **Stage 4 — messaging.** Ratcheted messages over Tor, SQLCipher history,
      offline send queue.
- [x] **Stage 5 — polish.** Safety-number verification, encrypted backup/restore,
      outbox retry, UX.
- [ ] **Future.** A store-and-forward *mailbox* for fully asynchronous delivery
      (today both peers must be online at the same time), group messaging,
      multi-device, and an independent security review.

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
