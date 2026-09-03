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

## 6. 流式 WAV 的日志时长失真

data size = 0xFFFFFFFF 的流式 WAV，`duration` 按无符号值计算得出约 6 小时的荒谬时长。数据读取本身安全（受 EOF 约束），仅日志失真。
