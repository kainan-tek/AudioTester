# AudioTester

中文 | [English](README_EN.md)

面向 Android Automotive OS（AAOS）车机的音频测试工具，包含基于 AudioTrack 的播放与基于 AudioRecord 的录音功能。

## 功能
- 顶部 Tab 切换「播放」/「录音」，两特性互斥（切 Tab 即停）
- 播放：内置 10s 扫频音源（`asset://sample/48k_2ch_16bit.wav`），默认无需推 WAV；也可配置 `/data/xx.wav`
- 录音：默认输出到 App 私有目录自动命名；系统应用可配置 `/data/` 固定路径
- 配置：单个 `assets/audio_configs.json`（JSONC，支持 `//` 与 `/* */` 注释），长按 Spinner 重载
- 部署：普通安装核心可用；系统应用部署可用系统专属功能（`/data` 热更新等，取决于车机框架支持）

## 配置说明
`audio_configs.json` 含 `player` / `recorder` 两个 section。外部热更新文件放 `/data/audio_configs.json`（需系统权限/root）。标记 `[需系统权限]` 的配置在普通安装下会失败，属预期行为。

## 部署说明
**普通安装**（`adb install`）：核心功能可用（内置音源播放、录音到 App 私有目录、assets 配置）。以下系统专属能力**不可用**（预期）：
- `/data` 配置热更新、`/data/xx.wav` 播放、`/data` 固定录音路径
- AAOS usage（1000-1004）配置 → `Invalid usage`
- 系统音源（ECHO_REFERENCE/RADIO_TUNER/HOTWORD/ULTRASOUND）→ `Invalid audio source`

**系统应用部署**（priv-app，userdebug/eng 构建）：
```bash
adb uninstall com.example.audiotester          # 1. 先卸载普通安装
# 2. 用平台系统密钥签名 APK
adb root && adb remount                        # 3. 获取系统分区写权限
adb push AudioTester.apk /system/priv-app/AudioTester/AudioTester.apk  # 4. 文件名=目录名
# 5.（建议）在 /system/etc/permissions/ 加 privapp-permissions-com.example.audiotester.xml 白名单
adb reboot                                      # 6. 重启生效
```
> priv-app 解决 `/data` 访问与存储权限；系统音频 usage/音源是否可用取决于车机 AAOS 框架支持，非安装方式决定。

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
