# AudioTester

中文 | [English](README_EN.md)

面向 Android Automotive OS（AAOS）车机的音频测试工具，包含基于 AudioTrack 的播放与基于 AudioRecord 的录音功能。

## 功能

顶部 Tab 切换「播放」/「录音」，两特性互斥（切 Tab 即停）。

### 播放

- **18 种音频场景**（媒体/语音通话/通话信令/闹钟/通知/通知事件/铃声/游戏/导航/辅助/系统提示音/语音助手/96kHz 高解析 + 5 种系统 usage：紧急/安全/车辆状态/广播/扬声器清理），每种可配 usage/contentType/performanceMode；系统 usage 需系统部署，见「高级：系统级部署」
- 内置 20s 粉红噪声音源（`asset://sample/48k_2ch_16bit.wav`），默认无需推 WAV；也可配置 `/data/xx.wav` 真实文件
- 完整音频支持：**1-16 声道**（含 5.1/7.1/5.1.4/7.1.4）、**8kHz-192kHz**、**8/16/24/32 位 PCM**
- 音频焦点管理：焦点被抢占时自动停止

### 录音

- **15 种音源**（默认/麦克风/语音上行/下行/双向/摄像/语音识别/远程混音/未处理/语音性能/系统级音源）
- 可配采样率/声道/位深，输出**头信息正确的有效 WAV**
- 默认输出到 App 私有目录自动命名；系统应用可配置 `/data/` 固定路径（见「高级：系统级部署」）

## 环境要求

- 设备：Android 12L（API 32）及以上；推荐 AAOS 车机或 AAOS 模拟器
- 构建：JDK 21 + Android SDK（compileSdk 37）；换机构建需设 `JAVA_HOME`（或在 `gradle.properties` 添加 `org.gradle.java.home`）指向本机 JDK
- 多声道/高采样率等播放能力上限取决于设备音频框架支持

## 快速开始

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App，「播放」Tab 选配置 → Start（内置音源开箱即用，无需推文件）
2. 「录音」Tab → Start → 授予麦克风权限 → Stop，WAV 默认生成在 App 私有目录

> **权限提示**：录音权限仅在首次点击 Start 时请求；若拒绝并选择"不再询问"，之后只显示失败、不再弹窗。需到 系统设置 → 应用 → AudioTester → 权限 手动重新开启。

## 日志与调试

```bash
# 配置加载日志
adb logcat -s ConfigLoader AudioConfig

# 播放/录音详细日志
adb logcat -s AudioPlayer AudioRecorder
```

## 配置说明

`audio_configs.xml`（assets 内置）含 `player` / `recorder` 两个 section，元素式 XML，每条配置为一个 `<config>`，原生支持注释。字段可省略（走默认值）；单条非法只跳过该条，不影响其余配置。

### 字段参考

| 字段 | 适用 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `usage` | 播放 | `USAGE_MEDIA` | 音频用途，13 种 SDK 场景 + 5 种系统场景见内置配置；系统场景需系统部署 |
| `contentType` | 播放 | `CONTENT_TYPE_MUSIC` | 内容类型 |
| `performanceMode` | 播放 | `PERFORMANCE_MODE_POWER_SAVING` | 省电 / 低延迟 |
| `bufferMultiplier` | 两者 | `2` | 最小缓冲倍数；须为正整数，非法条目被跳过 |
| `audioSource` | 录音 | `MIC` | 录音音源，15 种 |
| `sampleRate` | 录音 | `48000` | 采样率（8k-192k） |
| `channelCount` | 录音 | `2` | 仅 {1,2,8,10,12,14,16} 生效，见已知限制 |
| `audioFormat` | 录音 | `16` | 位深 8/16/24/32 |
| `audioFilePath` | 两者 | 空 | 播放：空 = 内置音源，或 `asset://…`、`/data/xx.wav`；录音：空 = 私有目录自动命名，或固定输出路径 |
| `description` | 两者 | `Custom configuration` | Spinner 显示名 |

### 示例

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

外部热更新：将配置放 `/data/audio_configs.xml`（优先于 assets，需系统权限，见「高级：系统级部署」）。标记 `[需系统权限]` 的配置在普通安装下会失败，属预期行为。

## 已知限制

- 录音 `channelCount` 仅支持 {1,2,8,10,12,14,16}（输入通道掩码可表示）；4/6 等其他值会被系统静默录成立体声
- AAOS 模拟器：从快照恢复后短时间内，音频焦点请求可能被拒（点 Start 报错），等待几分钟重试即可

## 高级：系统级部署

### 能力矩阵

| 能力 | 普通安装 | 系统应用（priv-app） |
| --- | --- | --- |
| 内置音源播放、录音到私有目录、assets 配置 | ✅ | ✅ |
| `/data` 配置热更新、`/data` WAV 播放、固定录音路径 | ❌ | ✅（仍受 SELinux/DAC 管控） |
| 系统音源（ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND） | ❌ `Invalid audio source` | 取决于车机框架支持 |
| 系统 usage 播放（USAGE_EMERGENCY 等 5 种） | ❌ 初始化失败 | ✅（需 MODIFY_AUDIO_ROUTING 白名单；SPEAKER_CLEANUP 另需 feature flag） |

### /data 文件访问

App 读 `/data` 下配置/WAV 会被系统安全策略拦截（`chmod 644` 无效），调试设备可临时放行：

```bash
adb root && setenforce 0
```

之后 App 即可读取（文件仍需 644）。写新文件到 `/data/` 需预建 App 可写目录：

```bash
adb shell mkdir /data/audio && adb shell chown <app_uid> /data/audio
```

量产需在系统策略放行；或将 WAV/输出放 App 私有目录（配置文件热更新需另行支持）。

### 系统应用部署（priv-app，userdebug/eng 构建）

```bash
adb uninstall com.example.audiotester          # 1. 先卸载普通安装
# 2. 用平台系统密钥签名 APK
adb root && adb remount                        # 3. 获取系统分区写权限
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. 文件名=目录名
# 5.（建议）在 /system/etc/permissions/ 加 privapp-permissions-com.example.audiotester.xml 白名单
#    需包含签名权限：CAPTURE_AUDIO_OUTPUT / CAPTURE_AUDIO_HOTWORD / MODIFY_AUDIO_ROUTING
#    （授予后系统录音音源 ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND 与
#     播放系统 usage USAGE_EMERGENCY 等才有机会工作）
adb reboot                                      # 6. 重启生效
```

> priv-app 授予系统签名与特权权限；`/data` 下文件读写仍受 SELinux/DAC 管控（见「/data 文件访问」）。系统录音音源（ECHO_REFERENCE 等）是否可用取决于车机 AAOS 框架支持，非安装方式决定。

## 开发

内置音源替换：修改 `tools/gen_pink_noise_wav.py` 后重新运行 `python tools/gen_pink_noise_wav.py`（默认生成 48k 内置音源；`python tools/gen_pink_noise_wav.py 96k32bit` 生成 hi-res 测试文件）。

## 手动验证清单

设备相关的人工验证项：

1. 两 Tab 切换正常，各自加载对应 section 配置
2. 播放默认配置直接出声（内置音源）；指向 `/data/xx.wav` 的配置可播放真实文件
3. 播放中切到录音 Tab → 播放停止；录音中切回播放 Tab → 录音停止；无法同时播放+录音
4. 录音输出 WAV 正常生成（路径/头信息/时长正确）
   - 提示：默认输出到 App 私有目录，可取 `adb pull /sdcard/Android/data/com.example.audiotester/files/`（或查 logcat 的 `Output file created:` 日志）
5. 长按 Spinner 重载配置生效（含 XML 注释）
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
- **邮箱**: <kainanos@outlook.com>
- **GitHub**: <https://github.com/kainan-tek/AudioTester>
- **问题反馈**: <https://github.com/kainan-tek/AudioTester/issues>

---

<div align="center">

**如果这个项目对你有帮助，请给个 ⭐ Star！**

Made with ❤️ by kainan-tek

[⬆ 回到顶部](#audiotester)

</div>
