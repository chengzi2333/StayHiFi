<div align="center">
  <img src="docs/assets/logo.png" width="128" alt="StayHiFi logo">
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

StayHiFi takes that chore off your plate: the instant a headset connects, it writes the parameters back to the maximum your device has negotiated — silently, in the background. And if maxing out leaves you with no sound or artifacts, or you simply prefer a different tuning, Custom Quality lets you dial codec / sample rate / bit depth / bitrate down manually and return to adaptive with one tap.

## Screenshots

<p>
  <img src="docs/assets/screenshots/01-home.png" width="260" alt="Home status card">
  <img src="docs/assets/screenshots/02-devices.png" width="260" alt="Devices page">
  <img src="docs/assets/screenshots/03-settings.png" width="260" alt="Settings page">
</p>

## Features

- **Maxed the moment you connect**: the target parameters are derived on the fly from what the current connection actually supports, and verified by reading the config back after writing; trigger merging and disconnect protection keep reconnects and playback starts steady
- **A status card that tells it straight**: "At this phone's maximum output quality" / "Not maxed out · tap to retry" / "Custom parameters active" / "Connected — authorize to continue" — no wishful claims
- **Auto re-max on reconnect**: a foreground service listens for connection events — event-driven, no idle polling
- **Custom Quality**: codec / sample rate / bit depth / bitrate can be adjusted manually within what the current connection supports; if maxing out leaves you with no sound or artifacts, step a notch down or return to adaptive with one tap — greyed-out choices are simply what your phone or headset doesn't offer right now. A custom setup is labeled "Custom" and never masquerades as "maxed out" — the default auto-max rule stays unchanged
- **Identity & live diagnostics**: verifies which codec your phone is actually running; on devices whose stack reports it, the card shows the live transmitted bitrate — and says so plainly when it can't be read. On some devices even a "fixed" bitrate stays adaptive, so trust the live readout
- **Persistent silent notification + QS tile**: optional live parameter readout; the QS tile re-applies the max with one tap, or opens the app when nothing is connected
- **iOS-style floating Dock navigation**: spring lens + jelly-physics motion, drag-tracking
- **Record current parameters**: the entry lives on the Devices page — capture the current negotiated parameters once and archive them, with device model / Android version provenance; model names resolve offline (v1.2.2)
- **One-tap restore anytime**: the Devices page can write the parameters back to the original negotiated values from before the first max-out
- **Material You**: dynamic colors, light and dark themes follow the system

## How it works

StayHiFi obtains shell-level privileges through **Shizuku or Stellar** (either one — no root required); devices that are already rooted can alternatively use the **Root channel (experimental)**, and switch back at any time. It then calls the system Bluetooth stack's hidden `setCodecConfigPreference()` API via reflection to write the A2DP codec configuration.

- It does **not** patch the system, unlock hidden codecs, or exceed the hardware-negotiated capability ceiling — Root only replaces the privilege channel; it does not raise the audio-quality ceiling
- Root-channel device coverage is still being validated with external samples and is not yet a stable, supported configuration — feedback welcome
- The system Bluetooth connection is decoupled from privileged reads: without Shizuku/Root set up (the normal BLUETOOTH_CONNECT permission still applies) you can still see this phone's A2DP connection and headset name; detailed parameters and any writes require the privileged authorization
- The only feature-level external interaction is local IPC with Shizuku / Stellar / Root; the sole network request is the GitHub Releases version check

## Install

> ⚠️ The only official channel right now is **GitHub Releases** (CoolApk listing in progress). Do not install APKs from anywhere else.

1. Set up privileges (pick one): install the **Shizuku** or **Stellar** manager and activate it per its instructions (wireless debugging / wired adb — no root required); devices that are already rooted can instead switch to the experimental Root channel inside the app
2. Download the APK from [GitHub Releases](https://github.com/chengzi2333/StayHiFi/releases) — **a single APK — no ABI variant to pick**; SHA-256 checksums and the signing-certificate fingerprint are attached to the release page, verify before installing

Requires: Android 10+.

## Usage

1. On first launch, follow the **four-step onboarding**: choose a privilege method → grant authorization → connect your headset → keep-alive settings. If pairing misbehaves, the connection step has a "Troubleshooting logs" entry — view and copy logs right there
2. The "three gates" of keep-alive (Chinese vendor ROMs kill background apps aggressively — do all three for reliability):
   - **Allow auto-launch** (system settings → app management)
   - **Add to battery whitelist** (disable battery optimization)
   - **Allow background high power usage + lock the recents card**: in Settings → Battery → background power management, allow this app; and in the recents view, pull the app card down and tap "lock" to prevent swipe-to-kill
3. That's it. When a headset connects, parameters are maxed automatically; the home status card tells you live whether you've hit the ceiling. To fine-tune, use "Custom Quality" on the Home page to adjust within what the current connection supports, then step down or return to adaptive if anything sounds wrong. Without the privileged authorization (the normal Bluetooth permission still applies) you still see the connected headset's name; parameters are shown and written only after authorization

## Uninstall

The written parameter preferences persist in the system Bluetooth stack — **uninstalling the app does not revert them automatically**. To go back to defaults: before uninstalling, tap "Restore default parameters" on the Devices page to write the parameters back to the original negotiated values from before the first max-out. Beyond that there is no residue — no system files are touched, so uninstalling leaves nothing behind.

## Feedback & contact

There are countless device models out there and vendor ROMs vary wildly, so some devices inevitably won't be covered. If you run into problems, feel free to reach out through any channel:

- **GitHub Issues**: the preferred place for bug reports and feature requests — attach the output of "Copy logs" on the in-app "Logs" page; the log is sanitized automatically (MAC addresses and device names are mapped to deviceN), so you can paste it as-is — please don't re-add raw unsanitized text
- **CoolApk**: @一般社畜橙子 (CoolApk handle)
- **Xiaohongshu**: @橙橙橙子Orenji (Xiaohongshu handle)

## Privacy

- **Zero data collection**: all data stays on your device only — no network uploads, no analytics, no account system
- **The only network request** is the GitHub Releases version check — it compares version numbers only and carries no device information (at most one silent check per day, or manually from the Settings page)
- All other external interaction is local IPC with Shizuku / Stellar / Root — whichever privilege channel you pick, it never leaves the device

## Disclaimer

This project has no affiliation with, sponsorship from, or endorsement by **Shizuku, Stellar, OPPO, or vivo** or their affiliates; all trademarks belong to their respective owners. The app only adjusts parameters of a hidden system API (called via reflection) **on your own device** — evaluate the risk yourself.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Third-party component notices in [NOTICE](NOTICE). Device-profile contribution guide in [CONTRIBUTING.md](CONTRIBUTING.md).

The source code is free to use and modify, but **the "StayHiFi" name and icon are not part of the license grant** — forks and redistributions must use a different name and icon (following the common practice of Shizuku / Seal).
