# AudioTester

An audio testing tool for Android Automotive OS (AAOS) cars, featuring AudioTrack-based playback and AudioRecord-based recording.

## Features
Top tabs switch between **Playback** / **Recording**; the two features are mutually exclusive (switching tabs stops the current one).

### Playback
- **12 audio scenarios** (media/voice call/call signaling/alarm/notification/notification event/ringtone/game/navigation/accessibility/system sound/voice assistant), each configurable via usage/contentType/transferMode/performanceMode
- Built-in 10s sweep source (`asset://sample/48k_2ch_16bit.wav`), no WAV file needed by default; can also use a `/data/xx.wav` real file
- Full audio support: **1-16 channels** (incl. 5.1/7.1/5.1.4/7.1.4), **8kHz-192kHz**, **8/16/24/32-bit PCM**
- Audio focus management: auto-stops when focus is taken

### Recording
- **15 audio sources** (default/mic/voice uplink/downlink/bidirectional/camcorder/voice recognition/remote submix/unprocessed/voice performance/system-level sources)
- Configurable sample rate/channels/bit depth, outputs **valid WAV with correct header**
- Defaults to an auto-named path in the app's private directory; system apps can configure a fixed `/data/` path (needs a pre-created writable directory, see "/data File Access")

## Quick Start

```bash
# View config loading logs
adb logcat -s ConfigLoader AudioConfig

# Check the external config file
adb shell cat /data/audio_configs.json

# Playback/recording detail logs
adb logcat -s AudioPlayer AudioRecorder
```

## Configuration
`audio_configs.json` has two sections: `player` and `recorder`. For external hot-reload, place the file at `/data/audio_configs.json` (needs root to relax SELinux, see "/data File Access"). Configs marked `[需系统权限]` (needs system privilege) fail on normal install — expected behavior.

## /data File Access
Apps reading config/WAV files under `/data` are blocked by the system security policy (`chmod 644` is not enough). On debug devices, temporarily relax it:

```bash
adb root && setenforce 0
```

After that the app can read the files (still needs 644). To write new files into `/data/`, pre-create a directory writable by the app:

```bash
adb shell mkdir /data/audio && adb shell chown <app_uid> /data/audio
```

For production, allow it in the system policy; or place WAV/output files in the app's private directory (config hot-reload needs extra support).

## Deployment
**Normal install** (`adb install`): core features work (built-in source playback, recording to app-private dir, assets config). The following system-only capabilities are **unavailable** (expected):
- `/data` config hot-reload, `/data/xx.wav` playback, fixed `/data` recording paths
- System audio sources (ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND) → `Invalid audio source`

**System-app deployment** (priv-app, userdebug/eng build):
```bash
adb uninstall com.example.audiotester          # 1. uninstall the normal install first
# 2. sign the APK with the platform system key
adb root && adb remount                        # 3. remount system partition for write access
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. filename must match the dir name
# 5. (recommended) add privapp-permissions-com.example.audiotester.xml under /system/etc/permissions/
#    include signature permissions: CAPTURE_AUDIO_OUTPUT / CAPTURE_AUDIO_HOTWORD
#    (enables system recording sources ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND)
adb reboot                                      # 6. reboot to apply
```
> priv-app grants system signature and privileged permissions; `/data` file access is still gated by SELinux/DAC (see "/data File Access"). Whether the system recording sources (ECHO_REFERENCE etc.) work depends on the AAOS framework support on the device, not the install method.

## Build & Install
JDK 21 required; when building on another machine, adjust `org.gradle.java.home` in `gradle.properties` or set `JAVA_HOME`.
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

## Related Projects

- [AAudioTester](https://github.com/kainan-tek/AAudioTester) - audio testing tool based on AAudio
- [audio_test_client](https://github.com/kainan-tek/audio_test_client) - system-level audio testing tool for Android

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

**Note**: This project is for learning and testing purposes only.

## Contact

- **Author**: kainan-tek
- **Email**: kainanos@outlook.com
- **GitHub**: https://github.com/kainan-tek/AudioTester
- **Issues**: https://github.com/kainan-tek/AudioTester/issues

---

<div align="center">

**If this project helps you, please give it a ⭐ Star!**

Made with ❤️ by kainan-tek

[⬆ Back to top](#audiotester)

</div>
