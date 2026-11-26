# ThorSubScreen

The external screen, extra keys and cursor of the ALT AT-M140 (`thor2`) for NineTailsFlip's LineageOS.

ThorSubScreen drives the **external screen** of the **ALT AT-M140 "MIVE Style Folder 2"**
(codename `thor2`), a Korean flip phone with a keypad: a clock, calls, notifications, music and
more on the outside of the lid. It also handles the phone's extra keys and offers a cursor for
apps that can't be used without touch.

## Features

### The outer screen

The outer screen is a 284 × 240 panel (GC9307C, over SPI). It's driven through the vendor's
`vendor.hnlens.hardware.presentation` HAL (`ILsPresentation`), the same way the stock firmware's
`LsPresentationService` does it.

Every page is drawn in the Nine look, like the apps on the main screen: black, plain text in
the font chosen in the phone's first-time setup (monospace unless proportional was chosen),
white for what matters, gray for the details, the page's title in small gray capitals and a hint
line at the bottom. No icons, emoji or colours; green only for what's going on (a call ringing,
charging).

- **Pages.** The SOS key flips through them while the screen is lit; pages that are turned off
  are skipped.
  - a clock: digits, or the time in words ("ten past four", «без пяти четыре»), with the date
    and your own signature line;
  - incoming and ongoing calls;
  - charging;
  - notifications, with or without their text (the outer screen is visible to everyone);
  - music: track and artist, playing or paused;
  - a kitty, drawn in letters, that sleeps at night, eats while charging and tells you about
    notifications;
  - a magic 8-ball: ask a question, press SOS.
- **Status line:** battery, how many new notifications, missed calls, steps today.
- **When it lights up:** closing the lid, the SOS key, the volume keys, new notifications, the
  charger being plugged in or out. Each one can be turned off.
- **Night mode:** at night the screen is dimmer, notifications don't light it up, and the kitty
  sleeps.
- **Flashlight:** a double press on SOS toggles it.

### Keys and lid

These are the keys apps don't handle themselves. `PhoneWindowManager` passes them on.

- Contacts (F1), Messages (F2), F3, Camera and the green key each have an action for a short
  press and one for a long press. The defaults:

  | Key | Short press | Long press |
  |---|---|---|
  | F1 | Contacts | — |
  | F2 | Messages (the default SMS app) | — |
  | F3 | Notifications | Cursor mode |
  | Camera | Camera | Flashlight |
  | Call (green) | Call log | Dialer |

- The lid can answer and end calls, like the stock firmware, if that's turned on.

### Cursor

`CursorService` is an accessibility service. In cursor mode the D-pad moves a pointer, OK taps,
a long OK long-presses, and the pointer scrolls when it reaches the edge of the screen. It only
captures keys while cursor mode is on.

## Installation

It's a persistent, platform-signed system app and comes with the image: it goes in
`packages/apps/ThorSubScreen` of the LineageOS tree and is pulled into the build by
[android_vendor_thor2](https://github.com/kloploid/android_vendor_thor2) (`thor2.mk`). It relies
on that repository's `frameworks/base` patches for the SOS, volume, lid and shortcut keys.

## Usage

The settings are in the phone's Settings app
([NineTailsFlip's Settings](https://github.com/NineTailsFlip/settings9)):

- *Settings → Display → Outer screen*: the pages, the clock, the status line, what lights it up,
  timeout, brightness, night mode, and "Show it now". The shade's quick settings switch it on and
  off.
- *Settings → System → Buttons and lid*: each key's short and long press, the red key, long
  press Home, the lid.

Settings reaches them through ThorSubScreen's provider `org.thor2.subscreen.prefs`
(`PrefsProvider`), which only holders of `WRITE_SECURE_SETTINGS` may use. They're kept in
device-protected storage, so the outer screen works before the phone is first unlocked.

The outer screen's HAL only answers SELinux domains in `hal_presentation_client`; the thor2
image's vendor (`vendor_stage3.img`, see android_vendor_thor2's *Flashing*) adds `system_app`,
ThorSubScreen's domain, to it.

## Building

It's an AOSP `android_app` (`Android.bp`). It needs `platform_apis` and the platform
certificate, because it uses `DEVICE_POWER`, `WRITE_SECURE_SETTINGS`, `CONTROL_KEYGUARD`,
`MODIFY_PHONE_STATE` and others. Build it with the rest of the ROM:

```bash
m ThorSubScreen
```

## Project structure

| Path | What's there |
|---|---|
| `src/org/thor2/subscreen/` | `SubScreenController` (when to show what), `Renderer` (draws a frame in the Nine look), `NineLook` (the font chosen in setup), `Panel` (the HAL), `KeyActions`, `CursorService`, `NotifyListener`, `PrefsProvider` (the settings, for Settings) |
| `aidl/vendor/hnlens/hardware/presentation/` | The vendor HAL's interfaces |
| `res/` | Strings (English and Russian), the cursor service config |

## Contributing

Issues and pull requests are welcome. Strings are in English and Russian (`res/values*/`): add
both when you add one.

## License

No license file yet.
