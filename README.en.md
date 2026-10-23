[简体中文](README.md) | [繁體中文](README.zh-TW.md) | [English] | [한국어](README.ko.md)

# StayHiFi 音质常驻

**Set it once — it stays that way.**

The moment your headset connects, StayHiFi pushes your Bluetooth audio (A2DP codec) parameters to the maximum your phone has negotiated for this device — silently, in the background. No more digging into Developer Options every time.

## Why this app exists

Vendors ship conservative Bluetooth codec defaults for compatibility and power reasons (e.g. SBC / 48 kHz / adaptive bitpool). To actually run your headset at its negotiated capability, you would otherwise have to:

- Open **Developer Options** after every connection and manually set codec / sample rate / bit depth;
- And the settings **roll back on disconnect or when Developer Options is closed** — every day starts over.

StayHiFi does exactly one thing: the instant a headset connects, it writes the parameters back.

## How it works

StayHiFi obtains shell-level privileges through **Shizuku or Stellar** (either one), and calls the system Bluetooth stack's hidden `setCodecConfigPreference()` API **via reflection** to write the A2DP codec configuration.

- It does **not** patch the system, unlock hidden codecs, or exceed the hardware-negotiated capability ceiling.
- A foreground service listens for connection events only — near-zero extra battery drain, no polling loop.
- There is no parameter-target stored in the app: the "max out" target is **derived on the fly** for your specific headset × phone × ROM combination (capability-mask maximum + a built-in best-tier table per codec), and **verified after writing** by reading the configuration back.

### Status model: two states

The home card shows one of exactly two states:

- **Not maxed out** — the current negotiated parameters are below this phone's maximum, and StayHiFi will push them up on connection.
- **At this phone's current maximum output quality** — the negotiated configuration already equals the derived target; the goal is reached and nothing further is pushed.

If the phone's own capability ceiling changes (codec mask or codec-tier table update), the target is re-derived automatically.

## Screenshots

<img src="docs/marketing/screenshots/01-home.png" width="280" alt="Home status card">
<img src="docs/marketing/screenshots/02-devices.png" width="280" alt="Devices page">
<img src="docs/marketing/screenshots/03-settings.png" width="280" alt="Settings page">

## Install

Official channels are only these two (CoolApk / GitHub). Do not install APKs from anywhere else:

- **CoolApk**: search "音质常驻" in the app market
- **GitHub Releases**: <https://github.com/chengzi2333/StayHiFi/releases> (SHA-256 of each APK is attached to the release page — verify before installing)

Prerequisite: install the **Shizuku** or **Stellar** manager and activate it per its own instructions (wireless debugging or wired adb — no root required).

Package name: `com.chengzi.stayhifi`.

## Usage

1. On first launch, follow the **five-step onboarding**: choose a privileged carrier (Shizuku / Stellar) → grant authorization → connect your headset → choose parameter source → configure keep-alive
2. The "three gates" of keep-alive (aggressive background-kill on Chinese vendor ROMs — do all three for reliability):
   - **Allow auto-launch** (system settings → app management)
   - **Add to battery whitelist** (disable battery optimization)
   - **Allow background high power usage + lock the recents card**: in Settings → Battery → background power management, allow this app; and in the recents view, pull the app card down and tap "lock" to prevent swipe-to-kill
3. That's it. When a headset connects, parameters are pushed to the maximum automatically; a silent persistent notification stays in the notification shade (optionally showing live parameters).

Adapting to a new device: no template is needed. The target is derived on the fly by the app per headset × phone × ROM (built-in best-tier table per codec), then verified by reading back after writing. The **record wizard** in onboarding is a diagnostic tool — it captures the current parameters once and stores them as a local record (it does not feed the max-out decision).

## Privacy

- **Zero data collection**: all data stays on your device only — no network uploads, no analytics, no account system.
- The only external interaction is local IPC with Shizuku / Stellar.

## Disclaimer

This project has no affiliation with, sponsorship from, or endorsement by **Shizuku, Stellar, OPPO, or vivo** or their affiliates; all trademarks belong to their respective owners. The app only adjusts parameters of a hidden system API (called via reflection) **on your own device** — evaluate the risk yourself.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Third-party component notices in [NOTICE](NOTICE). Device-profile contribution guide in [CONTRIBUTING.md](CONTRIBUTING.md).
