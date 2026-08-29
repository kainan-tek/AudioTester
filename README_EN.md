# AudioTester

[中文](README.md) | English

An audio testing tool for Android Automotive OS (AAOS) cars, featuring AudioTrack-based playback and AudioRecord-based recording.

## Features

Top tabs switch between **Playback** / **Recording**; the two features are mutually exclusive (switching tabs stops the current one).

### Playback

- **18 audio scenarios** (media/voice call/call signaling/alarm/notification/notification event/ringtone/game/navigation/accessibility/system sound/voice assistant/96kHz hi-res + 5 system usages: emergency/safety/vehicle status/announcement/speaker cleanup), each configurable via usage/contentType/performanceMode; system usages require system deployment (see "Advanced: System Deployment")
- Built-in 20s pink noise source (`asset://sample/48k_2ch_16bit.wav`), no WAV file needed by default; can also use a `/data/xx.wav` real file
- Full audio support: **1-16 channels** (incl. 5.1/7.1/5.1.4/7.1.4), **8kHz-192kHz**, **8/16/24/32-bit PCM**
- Audio focus management: auto-stops when focus is taken

### Recording

- **15 audio sources** (default/mic/voice uplink/downlink/bidirectional/camcorder/voice recognition/remote submix/unprocessed/voice performance/system-level sources)
- Configurable sample rate/channels/bit depth, outputs **valid WAV with correct header**
- Defaults to an auto-named path in the app's private directory; system apps can configure a fixed `/data/` path (see "Advanced: System-level Deployment")

## Requirements

- Device: Android 12L (API 32)+; an AAOS head unit or AAOS emulator is recommended
- Build: JDK 21 + Android SDK (compileSdk 37); when building on another machine, set `JAVA_HOME` (or add `org.gradle.java.home` in `gradle.properties`) to point to your local JDK
- Playback capability limits (multi-channel / high sample rates) depend on the device audio framework

## Quick Start

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open the app, pick a config on the **Playback** tab → Start (the built-in source works out of the box, no file needed)
2. **Recording** tab → Start → grant the microphone permission → Stop; the WAV lands in the app-private directory by default

> **Permission tip**: the recording permission is requested only on the first Start tap; if you deny it with "don't ask again", subsequent attempts just fail with no dialog. Re-enable it via system Settings → Apps → AudioTester → Permissions.

## Logging & Debugging

```bash
# Config loading logs
adb logcat -s ConfigLoader AudioConfig

# Playback/recording detail logs
adb logcat -s AudioPlayer AudioRecorder
```

## Configuration

`audio_configs.xml` (bundled in assets) has two sections: `player` and `recorder` — element-style XML, one `<config>` per entry, comments supported natively. Fields are optional (defaults apply); a single invalid entry is skipped without affecting the rest.

### Field Reference

| Field | Section | Default | Notes |
| --- | --- | --- | --- |
| `usage` | player | `USAGE_MEDIA` | audio usage; 12 scenarios in the bundled config |
| `contentType` | player | `CONTENT_TYPE_MUSIC` | content type |
| `performanceMode` | player | `PERFORMANCE_MODE_POWER_SAVING` | power saving / low latency |
| `bufferMultiplier` | both | `2` | min-buffer multiplier; must be a positive integer, invalid entries are skipped |
| `audioSource` | recorder | `MIC` | recording source, 15 available |
| `sampleRate` | recorder | `48000` | sample rate (8k-192k) |
| `channelCount` | recorder | `2` | only {1,2,8,10,12,14,16} take effect, see Known Limitations |
| `audioFormat` | recorder | `16` | bit depth 8/16/24/32 |
| `audioFilePath` | both | empty | playback: empty = built-in source, or `asset://…` / `/data/xx.wav`; recording: empty = auto-named private dir, or a fixed output path |
| `description` | both | `Custom configuration` | name shown in the Spinner |

### Example

```xml
<player>
  <config>
    <usage>USAGE_GAME</usage>
    <contentType>CONTENT_TYPE_MUSIC</contentType>
    <performanceMode>PERFORMANCE_MODE_LOW_LATENCY</performanceMode>
    <bufferMultiplier>2</bufferMultiplier>
    <description>My Game Scene</description>
  </config>
</player>
```

External hot-reload: place the file at `/data/audio_configs.xml` (takes priority over assets, needs system privilege, see "Advanced: System-level Deployment"). Configs marked `[需系统权限]` (needs system privilege) fail on normal install — expected behavior.

## Known Limitations

- Recording `channelCount`: only {1,2,8,10,12,14,16} take effect (representable as input channel masks); other values such as 4/6 are silently recorded as stereo
- AAOS emulator: shortly after restoring from a snapshot, audio focus requests may be rejected (Start reports an error); retry after a few minutes

## Advanced: System-level Deployment

### Capability Matrix

| Capability | Normal install | System app (priv-app) |
| --- | --- | --- |
| Built-in source playback, recording to private dir, assets config | ✅ | ✅ |
| `/data` config hot-reload, `/data` WAV playback, fixed recording paths | ❌ | ✅ (still gated by SELinux/DAC) |
| System sources (ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND) | ❌ `Invalid audio source` | depends on device framework |
| System usage playback (USAGE_EMERGENCY etc., 5 types) | ❌ init fails | ✅ (needs MODIFY_AUDIO_ROUTING whitelist; SPEAKER_CLEANUP also needs feature flag) |

### /data File Access

Apps reading config/WAV files under `/data` are blocked by the system security policy (`chmod 644` is not enough). On debug devices, temporarily relax it:

```bash
adb root && setenforce 0
```

After that the app can read the files (still needs 644). To write new files into `/data/`, pre-create a directory writable by the app:

```bash
adb shell mkdir /data/audio && adb shell chown <app_uid> /data/audio
```

For production, allow it in the system policy; or place WAV/output files in the app's private directory (config hot-reload needs extra support).

### System-app Deployment (priv-app, userdebug/eng build)

```bash
adb uninstall com.example.audiotester          # 1. uninstall the normal install first
# 2. sign the APK with the platform system key
adb root && adb remount                        # 3. remount system partition for write access
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. filename must match the dir name
# 5. (recommended) add privapp-permissions-com.example.audiotester.xml under /system/etc/permissions/
#    include signature permissions: CAPTURE_AUDIO_OUTPUT / CAPTURE_AUDIO_HOTWORD / MODIFY_AUDIO_ROUTING
#    (enables system recording sources ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND)
adb reboot                                      # 6. reboot to apply
```

> priv-app grants system signature and privileged permissions; `/data` file access is still gated by SELinux/DAC (see "/data File Access"). Whether the system recording sources (ECHO_REFERENCE etc.) work depends on the AAOS framework support on the device, not the install method.

## Development

Replace the built-in source: edit `tools/gen_pink_noise_wav.py` and re-run `python tools/gen_pink_noise_wav.py` (defaults to the 48k bundled sample; `python tools/gen_pink_noise_wav.py 96k32bit` generates the hi-res test file).

## Manual Verification Checklist

Device-dependent items, verified manually:

1. Both tabs switch correctly, each loads its own section configs
2. Default playback works out of the box (built-in source); configs pointing to `/data/xx.wav` can play real files
3. Switching to the Recording tab while playing stops playback; switching back stops recording; cannot play and record simultaneously
4. Recording produces a valid WAV (path/header/duration correct)
   - Tip: output defaults to the app-private dir; retrieve via `adb pull /sdcard/Android/data/com.example.audiotester/files/` (or check the `Output file created:` logcat line)
5. Long-press Spinner reload works (including XML comments)
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
- **Email**: <kainanos@outlook.com>
- **GitHub**: <https://github.com/kainan-tek/AudioTester>
- **Issues**: <https://github.com/kainan-tek/AudioTester/issues>

---

<div align="center">

**If this project helps you, please give it a ⭐ Star!**

Made with ❤️ by kainan-tek

[⬆ Back to top](#audiotester)

</div>
