# AudioTester

An audio testing tool merged from AudioPlayer (AudioTrack) and AudioRecorder (AudioRecord).

## Features
- Top tabs switch between **Playback** / **Recording**; the two features are mutually exclusive (switching tabs stops the current one)
- Playback: built-in 10s sweep source (`asset://sample/48k_2ch_16bit.wav`), no WAV file needed by default; can also use a `/data/xx.wav` file
- Recording: defaults to an auto-named path in the app's private directory; system apps can configure a fixed `/data/` path
- Config: single `assets/audio_configs.json` (JSONC, supports `//` and `/* */` comments), long-press the Spinner to reload
- Deployment: core features work on normal install; full features on system-app install (`/data` hot-reload, AAOS usages, system audio sources require system privileges)

## Configuration
`audio_configs.json` has two sections: `player` and `recorder`. For external hot-reload, place the file at `/data/audio_configs.json` (requires system privileges/root). Configs marked `[需系统权限]` (needs system privilege) fail on normal install — expected behavior.

## Deployment
**Normal install** (`adb install`): core features work (built-in source playback, recording to app-private dir, assets config). The following system-only capabilities are **unavailable** (expected):
- `/data` config hot-reload, `/data/xx.wav` playback, fixed `/data` recording paths
- AAOS usage (1000-1004) configs → `Invalid usage`
- System audio sources (ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND) → `Invalid audio source`

**System-app deployment** (priv-app, userdebug/eng build):
```bash
adb uninstall com.example.audiotester          # 1. uninstall the normal install first
# 2. sign the APK with the platform system key
adb root && adb remount                        # 3. remount system partition for write access
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. filename must match the dir name
# 5. (recommended) add privapp-permissions-com.example.audiotester.xml under /system/etc/permissions/
adb reboot                                      # 6. reboot to apply
```
> priv-app grants `/data` access and storage permissions; whether the system audio usages/sources work depends on the AAOS framework support on the device, not the install method.

## Build & Install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Replace the Built-in Source
Edit `tools/gen_sample_wav.py` and re-run: `python tools/gen_sample_wav.py`

## Manual Verification Checklist (device/emulator)
Device-dependent verification (spec §11), to be performed manually on the device:
1. Both tabs switch correctly, each loads its own section configs
2. Default playback works out of the box (built-in source); configs pointing to `/data/xx.wav` can play real files
3. Switching to the Recording tab while playing stops playback; switching back stops recording; cannot play and record simultaneously
4. Recording produces a valid WAV (path/header/duration correct)
   - Tip: output defaults to the app-private dir; retrieve via `adb pull /sdcard/Android/data/com.example.audiotester/files/` (or check the `Output file created:` logcat line)
5. Long-press Spinner reload works (including JSONC comments)
6. Permissions are requested on Start tap; clear feedback when denied
7. System-only configs fail on normal install without affecting other configs
