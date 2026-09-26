# 已知限制

代码审查（2026-09）确认、评估后**决定不修**的问题，记录在此避免重复调查成本。均不影响主流程正确性。

## 1. 录音中途保存失败对用户不可见

录音中 WAV 写盘失败（磁盘满等）后，`AudioRecorder` 置 `saveFailed` 继续录音，但 UI 仍显示正常"Recording..."，仅 logcat 有记录。

不修理由：上报需要从循环线程跨到 UI 的新通知路径，复杂度与价值不成比例（失败后数据已不可能恢复，知道与否不改变结果）。

## 2. RIFF 奇数 chunk 写侧不补 pad 字节

data chunk 长度为奇数时 RIFF size 为奇数（仅 blockAlign 为奇数的配置触发：8-bit 单声道、24-bit 单声道——后者 blockAlign=3，帧数为奇即奇；16/32 位与一切多声道恒为偶数。2026-09-26 勘误：原文误作"仅 8-bit 单声道"，漏计 24-bit 单声道）。多数播放器以 data chunk size 为准，播放不受影响；严格 RIFF 校验器会警告。读侧已正确处理 pad（写读不对称）。

## 3. 部分写后头部声明值可能略小于磁盘实际

`FileOutputStream.write` 部分落盘后抛 IOException 时 `dataLength` 不累加。播放器按声明值截断读取，功能无害；文件尾部残留越界字节。

## 4. 固定输出路径静默覆盖同名旧文件

`WavFile.create` 用 `FileOutputStream` 截断语义。仅当配置指定固定路径（如 `/data/test.wav`）连续录制时发生；自动生成的毫秒级时间戳路径不会撞名。

## 5. 主线程执行 WAV close/补头

`AudioViewModel.stop()` 在主线程直接调 `engine.stop()`（含 8 字节 `RandomAccessFile` 头部回写）。慢存储下可能短暂卡顿。焦点丢失回调路径已特意 post 到 IO 线程规避，其余 stop 路径未做（三路不一致，量级为毫秒级）。

此外 close 与 writeAudioData 共用 `@Synchronized` 监视器：主线程 close 还可能等待循环线程正在落盘的一整块缓冲（`bufferMultiplier`=100 时上限约 0.5MB，慢存储上数十毫秒）。该等待是正确性代价、不可移除——没有监视器，补头会漏计循环线程刚接受的一块写入，头部 data size 偏小导致文件截断（由 `closeRacingWithWriter_headerAlwaysMatchesAcceptedWrites` 测试守卫）。

播放侧同理：`readData` 与 close 共用同一监视器，主线程 stop/close 还可能等待循环线程正在读入的一整块缓冲（读缓冲与写缓冲同量级）。该等待同样是正确性代价、不可移除——没有监视器，close 会在 `readData` 中途关闭流，读取从半关闭的 InputStream 上失败。

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

## 10. Spinner 选中回声采用等值守卫而非 init-consume 标志（审查意见，评估后拒绝）

`AudioTestFragment.onItemSelected` 用 `selected == currentConfig.value` 识别程序化回声。审查指出重载配置时（先设 `availableConfigs` 后设 `currentConfig`）存在回声被误当作用户切换的窗口。拒绝理由：`onItemSelected` 在布局遍历时才派发、只携带最终选中位置，且两个 LiveData 观察者在同一主线程块内同步执行完毕——回调到达时 `currentConfig` 已是最终值，守卫正确早退；回滚到 init-consume 标志反而对"同步 / 异步 / 不回调"的平台投递怪癖更脆弱（等值守卫正是 `b00a7f6` 为替代该标志而引入）。残余影响：极怪异投递下一次多余 toast，状态收敛正确。另：两条字节级相同的配置条目之间切换会被等值守卫静默吞掉——切换成功与被吞功能差异为零，而按位置判断会因 `indexOf` 在重复条目上恒返回首个孪生而误触发，等值守卫恰对此免疫。

## 11. 播放器的 asset:// 是指定路径而非哨兵，未并入 hasUsableFilePath（审查意见，评估后拒绝）

`AudioPlayer.openResources` 的 `ifEmpty + startsWith("asset://")` 看似重复实现了 `hasUsableFilePath`，实为三分派：空串折叠为默认 asset、`asset://xxx` 打开**指定的** asset、其余读磁盘；录制器的 `asset://` 才是哨兵（自动生成输出路径）。若用 `hasUsableFilePath` 收敛，指定 asset 的配置会被静默替换为默认文件。`hasUsableFilePath` 表达的是粗粒度分类（权限门、显示层），表达不了引擎的分派粒度。

## 12. 两处 4 行级重复未抽取：OOM catch 与 friendlyMessage 公共条目（审查意见，评估后拒绝）

`catch (OutOfMemoryError)` 块（含策略注释）在两个引擎循环中逐字重复；STREAM/PARAM 友好文案在两个 Fragment 中逐字重复。拒绝理由：抽 helper 净行数为零、多一跳间接；friendlyMessage 上移基类需死分支兜底，并把每个 Fragment 的穷尽 `when` 劈成两半——新增 `AudioErrorType` 时子类不再编译失败，丢失 fail-loud 检查（现连不可达分支都特意保留，见 `AudioErrorType` 文档注释）。三行级重复优于过早抽象。

## 13. 枚举三重查找并存：前置门 + 兜底（审查意见，评估后拒绝）

`findUnknown*Enum`（启动前置门，触碰文件系统前具名拒绝）与 `parseEnumValue` 的 `requireNotNull`（门被绕过时的 fail-loud 兜底）是纵深防御而非冗余；两个 `findUnknown*` 是各自领域的字段清单（聚合 arity 不同）而非重复代码，抽通用 helper 不减少新增字段的触碰点。备选方案"`start()` 捕获 IllegalArgumentException → PARAM"被否：`setUsage` 传 system usage（1000-1004）必然抛 IAE（设备不支持 ≠ 配置拼错），全局映射混淆两类失败，且丢失"触碰文件系统前拒绝"的时序保证。同方向决策见 §9。

## 14. 重载守卫的 ALREADY_ACTIVE 弹窗语义失准（不可达防御分支）

`AudioViewModel.reloadConfigurations` 的 ACTIVE/STARTING 守卫弹 "…is already in progress."，不直接回答"重载怎么了"（准确文案 "Cannot reload configuration while active" 在 `statusMessage`，且被弹窗遮住）。但该分支实际不可达：`_state` 的 LiveData `setValue` 在主线程同步派发，spinner 在 STARTING/ACTIVE 同一时刻被禁用，禁用的 View 不派发长按，而长按是 `reloadConfigurations` 唯一生产入口。守卫是 `2b87c48` 特意加的回归防线，保留；为死分支改文案或加机制收益为零。

## 15. release 期 FINALIZE 错误就地直报，不走 handleError（审查意见，评估后拒绝）

`AudioRecorder.releaseAudioResources` 直接 `engineListener?.onError(FINALIZE, ...)` 并自带 `state != AudioState.ERROR` 抑制，依赖"先 onError 后 onStopped"的跨文件顺序不变量（两端注释 + `AudioViewModel.onStopped` 守卫锚定）。拒绝理由：全库仅此一个可终结资源（播放器 `wavFile?.close()` 不上报失败），抽 `reportReleaseError` helper 不消除两端契约、净行数为零；且若走 `handleError` 会重入 `releaseAudioResources`（此时 `wavFile` 尚未置 null），就地直报恰好绕开该递归。

## 16. 播放截断判定与 stop() 的纳秒级竞态窗口（审查意见，评估后不修）

`AudioPlayer.startLoop` 的截断判定（`hasUnreadDeclaredData`）在 `state == ACTIVE` 读取**之后**求值；若 `stop()` 全程（IDLE → cancel → `releaseAudioResources` → `close()` 清零 `remainingData`）恰好塞进这两条相邻语句之间（loop 线程恰在此被抢占），截断文件会被记为 "Playback completed" 而非 TRUNCATED，丢失诊断。

不修理由：窗口为纳秒级——需 loop 线程恰在两条语句间被抢占，且 stop() 的 AudioTrack release + 磁盘 IO 完整落入该间隙，实际不可观测。修复方案已验证可行（判定快照提前到 state 读取之前，2 行；消息值的第二次读仅在 `handleLoopError` 真正报错、即无并发 close 时被消费），但收益仅限该极端场景，相邻交错（stop 先于判定 / 后于判定）的行为本已正确。

## 17. STARTING 期销毁竞态下 FINALIZE 错误被吞（审查发现，评估后不修）

三重巧合窗口：STARTING 期间 `onPause`（只置 `stopRequested` 标志）→ Activity 在引擎提交 ACTIVE 前销毁（androidx 先取消 `viewModelScope` 再调 `onCleared`）→ `release()` 的 `wavFile.close()` 补头失败。此时 `onError(FINALIZE)` 经 `updateUI` 落在已取消的 scope 上被丢弃，用户留下头部不完整的不可读 WAV，唯一痕迹是 logcat（`AudioRecorder.kt` 的就地直报注释已锚定）。

注意窗口比三重巧合更窄：仅 `onPause` 未销毁时 ViewModel 存活，`onStarted` 的纠正性 stop 正常执行，错误写入 LiveData、回来后被新观察者消费（`AudioTestFragment` 的 consume-on-delivery 注释）；丢失仅发生在 scope 先死的完全销毁路径。

不修理由：错误发生时 Activity 已销毁、ViewModel 即将死亡，无任何存活接收者（LiveData/对话框/Toast 均随生命周期消亡）——真正送达需引入跨生命周期机制（通知或持久化错误标记），为三重巧合下的诊断信息丢失不成比例。失效模式是诊断缺失而非功能错误，不可读文件在下次播放尝试时自然暴露。

## 18. start() 阶段 OOM 崩溃，与 loop 侧"报告而非崩溃"政策不对称（审查发现，评估后不修）

`AudioEngineBase.start()` 的 catch 链只有 `SecurityException` + `Exception`，start 期间若抛 `OutOfMemoryError` 会穿透 ViewModel 协程崩溃进程；两个引擎的循环则刻意 catch OOM 并报告（政策注释见各 `startLoop`）。

不修理由：两条 catch 路径覆盖的是不同的分配点——loop 侧守着真实的大 Java 堆分配（`ByteArray(writeBufferSize)`，bufferMultiplier=100 时数十 MB），风险真实存在；start 侧无对应物，大缓冲是 native 分配（不足时以 IAE/ISE 浮出，已被现有 catch 链覆盖），Java 堆侧只有 Builder 内部小分配。现实时序下即使堆紧张，start 的小分配挤过去后 loop 的大分配 OOM 也由 loop catch 接住；要让 start 侧 catch 起作用需连小分配都失败的深度堆耗尽，而那时 `handleError` 自身的分配同样会崩——catch 只是换了个崩溃点。为不存在的分配点复制政策，属于为假设场景加代码。

## 19. system usage 在普通安装上的确定性失败归类为 STREAM（审查发现，评估后不修）

普通安装上选择 system-usage 配置（1000-1004）时，`setSystemUsageReflectively` 必然抛 IAE（缺 `MODIFY_AUDIO_ROUTING`），被 `AudioPlayer.initializeAudio` 的 `catch (Exception)` 归为 STREAM，弹窗显示"Audio system initialization failed. Please try again."——重试对确定性失败无效，文案存在误导。

不修理由：失败是设计预期——这批配置为系统部署（AAOS）准备，spinner 选项名自带 `[requires system permission]` 标注，logcat 有精确诊断（usage 值 + 所需权限）。现有 8 类错误类型中无准确归属：PARAM 已被 §13 否决（设备不支持 ≠ 配置拼错），新增 UNSUPPORTED 类型需为一个自标注的设计内失败扩充错误分类法（枚举 + 双 Fragment 穷尽 when + 测试），不成比例。STREAM 是现有分类法中最不坏的选择（失败确实发生在音频系统初始化阶段）。

## 20. 录音器循环无 CancellationException rethrow，与播放器不对称（审查发现，评估后不修）

播放器循环因 drain 轮询的 `delay()`（挂起点）需要显式 rethrow（`AudioPlayer.startLoop`）；录音器 try 块全为阻塞调用（`audioRecord.read` / `wavFile.writeAudioData`），无挂起点——取消经 `state == ACTIVE` 守卫自然退出，`CancellationException` 在该路径上结构性无法产生，补 rethrow 是死代码。

不修理由：不对称是原理性的（有无挂起点），非疏漏；`handleLoopError` 的"IDLE 先于 cancel"契约（见其文档注释）提供第二道防线。若未来录音器循环引入挂起点，届时按播放器同款补上即可。

## 21. pending-error 生命周期靠多点约定而非单一 consume 机制（审查意见，评估后拒绝）

审查建议把"非空 `errorMessage` 即未投递；ERROR 不留 null 消息"的不变量收敛为 VM 自带的 `consumeError()` / 一次性错误事件。拒绝理由：`errorMessage` 的 sticky LiveData 语义**本身就是投递机制**——未投递错误跨视图重建送达新观察者（finalize 失败存活于视图销毁）、消费即清除防重放，两端均有测试钉住（`failed start not yet consumed keeps the error pending` / `failed start consumed by the UI does not re-enter ERROR`）。一次性事件需重新实现该 sticky 语义才能保住这些特性，负净值。且各写入点不是同一操作的重复，而是围绕共享值的异构策略（投递 / 新动作清场 / pending 优先于停止确认 / 已消费不重入），单一 consume 无法统一。各点注释即不变量的文档。

## 22. readLittleEndianInt 保留 offset 参数 + SameParameterValue 豁免（审查意见，评估后拒绝）

审查建议删参硬编码 4（两个调用点均传 4）。拒绝理由：二进制解析代码中调用点显式 offset 是自文档（与 `readLittleEndianShort(fmt, 2)/(fmt, 14)` 及 `String(bytes, offset, 4, …)` 的既有风格一致），删参后结构知识被藏进函数体、出现魔法数，且与紧邻的 Short helper（多 offset、参数必要）形成特例成员。`SameParameterValue` 不检测错误 offset（其语义是"所有调用点同值 → 建议删参"），禁用它未关闭任何正确性防线；豁免的代价仅 1 注解 + 2 行注释。
