# AudioTester

中文 | [English](README_EN.md)

面向 Android Automotive OS（AAOS）车机的音频测试工具，包含基于 AudioTrack 的播放与基于 AudioRecord 的录音功能。

## 功能
顶部 Tab 切换「播放」/「录音」，两特性互斥（切 Tab 即停）。

### 播放
- **12 种音频场景**（媒体/语音通话/通话信令/闹钟/通知/通知事件/铃声/游戏/导航/辅助/系统提示音/语音助手），每种可配 usage/contentType/performanceMode
- 内置 10s 扫频音源（`asset://sample/48k_2ch_16bit.wav`），默认无需推 WAV；也可配置 `/data/xx.wav` 真实文件
- 完整音频支持：**1-16 声道**（含 5.1/7.1/5.1.4/7.1.4）、**8kHz-192kHz**、**8/16/24/32 位 PCM**
- 音频焦点管理：焦点被抢占时自动停止

### 录音
- **15 种音源**（默认/麦克风/语音上行/下行/双向/摄像/语音识别/远程混音/未处理/语音性能/系统级音源）
- 可配采样率/声道/位深，输出**头信息正确的有效 WAV**
- 默认输出到 App 私有目录自动命名；系统应用可配置 `/data/` 固定路径（需预建可写目录，见「/data 文件访问」）

## 快速开始

```bash
# 查看配置加载日志
adb logcat -s ConfigLoader AudioConfig

# 检查外部配置文件
adb shell cat /data/audio_configs.json

# 播放/录音详细日志
adb logcat -s AudioPlayer AudioRecorder
```

## 配置说明
`audio_configs.json` 含 `player` / `recorder` 两个 section。外部热更新文件放 `/data/audio_configs.json`（需 root 临时放行 SELinux，见「/data 文件访问」）。标记 `[需系统权限]` 的配置在普通安装下会失败，属预期行为。

## /data 文件访问
App 读 `/data` 下配置/WAV 会被系统安全策略拦截（`chmod 644` 无效），调试设备可临时放行：

```bash
adb root && setenforce 0
```

之后 App 即可读取（文件仍需 644）。写新文件到 `/data/` 需预建 App 可写目录：

```bash
adb shell mkdir /data/audio && adb shell chown <app_uid> /data/audio
```

量产需在系统策略放行；或将 WAV/输出放 App 私有目录（配置文件热更新需另行支持）。

## 部署说明
**普通安装**（`adb install`）：核心功能可用（内置音源播放、录音到 App 私有目录、assets 配置）。以下系统专属能力**不可用**（预期）：
- `/data` 配置热更新、`/data/xx.wav` 播放、`/data` 固定录音路径
- 系统音源（ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND）→ `Invalid audio source`

**系统应用部署**（priv-app，userdebug/eng 构建）：
```bash
adb uninstall com.example.audiotester          # 1. 先卸载普通安装
# 2. 用平台系统密钥签名 APK
adb root && adb remount                        # 3. 获取系统分区写权限
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. 文件名=目录名
# 5.（建议）在 /system/etc/permissions/ 加 privapp-permissions-com.example.audiotester.xml 白名单
#    需包含签名权限：CAPTURE_AUDIO_OUTPUT / CAPTURE_AUDIO_HOTWORD
#    （授予后系统录音音源 ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND 才有机会工作）
adb reboot                                      # 6. 重启生效
```
> priv-app 授予系统签名与特权权限；`/data` 下文件读写仍受 SELinux/DAC 管控（见「/data 文件访问」）。系统录音音源（ECHO_REFERENCE 等）是否可用取决于车机 AAOS 框架支持，非安装方式决定。

## 构建与安装
需 JDK 21；如换机构建，需调整 `gradle.properties` 的 `org.gradle.java.home` 或设 `JAVA_HOME`。
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 内置音源替换
修改 `tools/gen_sample_wav.py` 后重新运行：`python tools/gen_sample_wav.py`

## 手动验证清单（真机/模拟器）
以下为设备相关的验证标准（spec §11），需人工在设备上操作：
1. 两 Tab 切换正常，各自加载对应 section 配置
2. 播放默认配置直接出声（内置音源）；指向 `/data/xx.wav` 的配置可播放真实文件
3. 播放中切到录音 Tab → 播放停止；录音中切回播放 Tab → 录音停止；无法同时播放+录音
4. 录音输出 WAV 正常生成（路径/头信息/时长正确）
   - 提示：默认输出到 App 私有目录，可取 `adb pull /sdcard/Android/data/com.example.audiotester/files/`（或查 logcat 的 `Output file created:` 日志）
5. 长按 Spinner 重载配置生效（含 JSONC 注释）
6. 点击 Start 才弹权限；拒绝后有明确提示
7. 普通安装下系统专属配置报错且不影响其他配置

## 相关项目

- [AAudioTester](https://github.com/kainan-tek/AAudioTester) - 基于 AAudio 的音频测试工具
- [audio_test_client](https://github.com/kainan-tek/audio_test_client) - Android 系统级音频测试工具

## 许可证

本项目采用 MIT License 许可证。详细信息请参阅 [LICENSE](LICENSE) 文件。

**注意**: 本项目仅供学习和测试使用。

## 联系方式

- **作者**: kainan-tek
- **邮箱**: kainanos@outlook.com
- **GitHub**: https://github.com/kainan-tek/AudioTester
- **问题反馈**: https://github.com/kainan-tek/AudioTester/issues

---

<div align="center">

**如果这个项目对你有帮助，请给个 ⭐ Star！**

Made with ❤️ by kainan-tek

[⬆ 回到顶部](#audiotester)

</div>
