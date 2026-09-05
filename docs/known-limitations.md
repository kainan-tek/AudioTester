# 已知限制

代码审查（2026-09）确认、评估后**决定不修**的问题，记录在此避免重复调查成本。均不影响主流程正确性。

## 1. 录音中途保存失败对用户不可见

录音中 WAV 写盘失败（磁盘满等）后，`AudioRecorder` 置 `saveFailed` 继续录音，但 UI 仍显示正常"Recording..."，仅 logcat 有记录。

不修理由：上报需要从循环线程跨到 UI 的新通知路径，复杂度与价值不成比例（失败后数据已不可能恢复，知道与否不改变结果）。

## 2. RIFF 奇数 chunk 写侧不补 pad 字节

data chunk 长度为奇数时 RIFF size 为奇数（仅 8-bit 单声道可能触发，16/24/32 位路径恒为偶数）。多数播放器以 data chunk size 为准，播放不受影响；严格 RIFF 校验器会警告。读侧已正确处理 pad（写读不对称）。

## 3. 部分写后头部声明值可能略小于磁盘实际

`FileOutputStream.write` 部分落盘后抛 IOException 时 `dataLength` 不累加。播放器按声明值截断读取，功能无害；文件尾部残留越界字节。

## 4. 固定输出路径静默覆盖同名旧文件

`WavFile.create` 用 `FileOutputStream` 截断语义。仅当配置指定固定路径（如 `/data/test.wav`）连续录制时发生；自动生成的毫秒级时间戳路径不会撞名。

## 5. 主线程执行 WAV close/补头

`AudioViewModel.stop()` 在主线程直接调 `engine.stop()`（含 8 字节 `RandomAccessFile` 头部回写）。慢存储下可能短暂卡顿。焦点丢失回调路径已特意 post 到 IO 线程规避，其余 stop 路径未做（三路不一致，量级为毫秒级）。

此外 close 与 writeAudioData 共用 `@Synchronized` 监视器：主线程 close 还可能等待循环线程正在落盘的一整块缓冲（`bufferMultiplier`=100 时上限约 0.5MB，慢存储上数十毫秒）。该等待是正确性代价、不可移除——没有监视器，补头会漏计循环线程刚接受的一块写入，头部 data size 偏小导致文件截断（由 `closeRacingWithWriter_headerAlwaysMatchesAcceptedWrites` 测试守卫）。

## 6. 流式 WAV 的日志时长失真

data size = 0xFFFFFFFF 的流式 WAV，`duration` 按无符号值计算得出约 6 小时的荒谬时长。数据读取本身安全（受 EOF 约束），仅日志失真。

## 7. 多声道录音不写 WAVE_FORMAT_EXTENSIBLE

写侧恒写 16 字节 fmt（PCM=1），>2 声道录音的物理布局信息不进文件（读侧支持 EXTENSIBLE，写读不对称）。第三方 DAW/播放器对多声道文件只能按惯例猜测声道含义；本管线自读自写不受影响（PCM(1) 多声道合法）。

不修理由：EXTENSIBLE 的 fmt 为 40 字节，`writeInitialWavHeader` 的硬编码偏移需全部参数化，`updateWavHeader` 的补头偏移（4/40）依赖头长度、必须随变体走，连带 maxDataBytes 与一批测试——约 40-60 行改动，买到的仅是互操作性。

## 8. XML 配置解析未禁用外部实体（XXE 面）

`DocumentBuilderFactory` 默认配置可解析 DOCTYPE/外部实体，配置源含 root 可写的 `/data/audio_configs.xml`。

不修理由：能放置恶意配置的前提是拥有测试设备 root，而 root 本就拥有设备与 app 的一切——加固不改变威胁模型，反而引入新失败模式（带 DOCTYPE 的合法配置会被拒绝、静默落回默认值）。

## 9. 枚举校验位于引擎启动期而非解析期（审查意见，评估后拒绝）

有审查意见建议把 `findUnknown*Enum` 校验从各引擎 `openResources` 移到 `parseConfigs` 解析边界。拒绝理由：现状下拼错的配置**留在 spinner 列表里可见**、Start 时给出指名道姓的 `[PARAM]` 错误；移到解析期则坏配置被 `runCatching` 静默跳过、从列表消失，测试者无从得知原因。可见性 + 启动时具名失败更符合 fail-loud 哲学；维护成本由对称的 `findUnknown*` 与 `EngineEnumValidationTest` 覆盖。
