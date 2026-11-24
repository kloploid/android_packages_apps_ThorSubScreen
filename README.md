# ThorSubScreen

The **external screen** of the **ALT AT-M140 "MIVE Style Folder 2"** (codename `thor2`), a
Korean flip phone with a keypad, for our LineageOS GSI. It also handles the phone's extra keys
and offers a cursor for apps that can't be used without touch.

It's a persistent, platform-signed system app. It goes in `packages/apps/ThorSubScreen` and is
pulled into the build by [android_vendor_thor2](https://github.com/kloploid/android_vendor_thor2)
(`thor2.mk`). It relies on that repository's `frameworks/base` patches for the SOS, volume, lid
and shortcut keys.

## What it does

### The outer screen

The outer screen is a 284 × 240 panel (GC9307C, over SPI). It's driven through the vendor's
`vendor.hnlens.hardware.presentation` HAL (`ILsPresentation`), the same way the stock firmware's
`LsPresentationService` does it.

- **Pages.** The SOS key flips through them while the screen is lit; pages that are turned off
  are skipped.
  - a clock: digital, analog, or the time in words ("ten past four", «без пяти четыре»), with
    the date, an accent colour, a background photo and your own signature line;
  - incoming and ongoing calls;
  - charging;
  - notifications, with or without their text (the outer screen is visible to everyone);
  - music: track, artist and cover;
  - a kitty that sleeps at night, eats while charging and tells you about notifications;
  - a magic 8-ball: ask a question, press SOS.
- **Status line:** battery, icons of apps with new notifications, missed calls, steps today.
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

### Settings

- *Settings → Display → Outer screen*. It's also the long press on the quick-settings tile.
  There's a live preview; tapping it flips pages.
- *Settings → System → Buttons and lid*.

Preferences are kept in device-protected storage, so the outer screen works before the phone is
first unlocked.

## Building

It's an AOSP `android_app` (`Android.bp`). It needs `platform_apis` and the platform
certificate, because it uses `DEVICE_POWER`, `WRITE_SECURE_SETTINGS`, `CONTROL_KEYGUARD`,
`MODIFY_PHONE_STATE` and others. Build it with the rest of the ROM:

```bash
m ThorSubScreen
```

## Layout

| Path | What's there |
|---|---|
| `src/org/thor2/subscreen/` | `SubScreenController` (when to show what), `Renderer` (draws a frame), `Panel` (the HAL), `KeyActions`, `CursorService`, `NotifyListener`, settings |
| `aidl/vendor/hnlens/hardware/presentation/` | The vendor HAL's interfaces |
| `res/` | Strings (English and Russian), settings screens, the cursor service config |
