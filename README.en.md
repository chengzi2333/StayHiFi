<div align="center">
  <img src="docs/marketing/logo.png" width="128" alt="StayHiFi logo">
  <h1>StayHiFi</h1>
  <p><strong>Bluetooth on, quality maxed — no root, set it up once and forget it</strong></p>
  <p><a href="README.md">简体中文</a> | <a href="README.zh-TW.md">繁體中文</a> | [English] | <a href="README.ko.md">한국어</a></p>
  <p>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/v/release/chengzi2333/StayHiFi" alt="Release"></a>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/downloads/chengzi2333/StayHiFi/total" alt="Downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/chengzi2333/StayHiFi" alt="License"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-green" alt="Android 10+">
  </p>
</div>

## Why StayHiFi

For compatibility and power reasons, vendors ship Bluetooth codecs with conservative defaults (SBC / 48 kHz / adaptive bitrate) — pay HiFi money, hear MP3 sound. To actually run your headset at its real capability, you'd otherwise have to dig into **Developer Options** after every connection and manually set codec / sample rate / bit depth — and the settings **roll back on disconnect or when Developer Options is closed**, so every day starts over.

StayHiFi does exactly one thing: the instant a headset connects, it writes the parameters back to the maximum your device has negotiated — silently, in the background.

## Screenshots

<p>
  <img src="docs/marketing/screenshots/01-home.png" width="260" alt="Home status card">
  <img src="docs/marketing/screenshots/02-devices.png" width="260" alt="Devices page">
  <img src="docs/marketing/screenshots/03-settings.png" width="260" alt="Settings page">
</p>

## Features

- **Maxed the moment you connect**: any Bluetooth headset, zero per-device adaptation — the target parameters are derived on the fly for your headset × phone × ROM combination, and verified by reading the config back after writing
- **Two-state status card**: "At this phone's maximum output quality" / "Not maxed out · tap to retry" — one glance tells all
- **Auto re-max on reconnect**: a foreground service listens for connection events — near-zero battery drain, no polling
- **Persistent silent notification**: optional live parameter readout; plus a QS tile for one-tap status
- **iOS-style floating Dock navigation**: spring lens + jelly-physics motion, drag-tracking
- **Record wizard** (optional diagnostic tool): capture the current negotiated parameters once and archive them to the Devices page, with device model / Android version provenance
- **Material You**: dynamic colors, light and dark themes follow the system

## How it works

StayHiFi obtains shell-level privileges through **Shizuku or Stellar** (either one), then calls the system Bluetooth stack's hidden `setCodecConfigPreference()` API via reflection to write the A2DP codec configuration.

- It does **not** patch the system, unlock hidden codecs, or exceed the hardware-negotiated capability ceiling
- The only feature-level external interaction is local IPC with Shizuku/Stellar; the sole network request is the GitHub Releases version check

## Install

> ⚠️ The only official channel right now is **GitHub Releases** (CoolApk listing in progress). Do not install APKs from anywhere else.

1. Install the **Shizuku** or **Stellar** manager and activate it per its instructions (wireless debugging / wired adb — no root required)
2. Download the APK from [GitHub Releases](https://github.com/chengzi2333/StayHiFi/releases) — **one universal package covers all devices**, no need to pick an ABI variant; SHA-256 checksums and the signing-certificate fingerprint are attached to the release page, verify before installing

Requires: Android 10+.

## Usage

1. On first launch, follow the **five-step onboarding**: choose a privileged carrier (Shizuku / Stellar) → grant authorization → connect your headset → choose parameter source → keep-alive settings
2. The "three gates" of keep-alive (Chinese vendor ROMs kill background apps aggressively — do all three for reliability):
   - **Allow auto-launch** (system settings → app management)
   - **Add to battery whitelist** (disable battery optimization)
   - **Allow background high power usage + lock the recents card**: in Settings → Battery → background power management, allow this app; and in the recents view, pull the app card down and tap "lock" to prevent swipe-to-kill
3. That's it. When a headset connects, parameters are maxed automatically; the home status card tells you live whether you've hit the ceiling

## Feedback & contact

There are countless device models out there and vendor ROMs vary wildly, so some devices inevitably won't be covered. If you run into problems, feel free to reach out through any channel:

- **GitHub Issues**: the preferred place for bug reports and feature requests — attaching the content of the in-app "Logs" page helps the most
- **CoolApk**: @一般社畜橙子 (CoolApk handle)
- **Xiaohongshu**: @橙橙橙子Orenji (Xiaohongshu handle)

## Privacy

- **Zero data collection**: all data stays on your device only — no network uploads, no analytics, no account system
- **The only network request** is the GitHub Releases version check — it compares version numbers only and carries no device information (at most one silent check per day, or manually from the Settings page)
- All other external interaction is local IPC with Shizuku/Stellar

## Disclaimer

This project has no affiliation with, sponsorship from, or endorsement by **Shizuku, Stellar, OPPO, or vivo** or their affiliates; all trademarks belong to their respective owners. The app only adjusts parameters of a hidden system API (called via reflection) **on your own device** — evaluate the risk yourself.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Third-party component notices in [NOTICE](NOTICE). Device-profile contribution guide in [CONTRIBUTING.md](CONTRIBUTING.md).

The source code is free to use and modify, but **the "StayHiFi" name and icon are not part of the license grant** — forks and redistributions must use a different name and icon (following the common practice of Shizuku / Seal).
