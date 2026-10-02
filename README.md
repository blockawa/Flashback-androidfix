# Flashback Android Fix

An Android fix mod for [Flashback](https://modrinth.com/mod/flashback) that lets it launch, record, and export properly on Android.

## Features

- **Bypasses native dialogs** — The native SDL dialogs for video/screenshot export never return on Android; the mod instead reads and writes the folder configured in export settings directly.
- **Export path persistence** — The path input moves into Preferences and survives restarts; a configured path is no longer quietly reset to the game directory by Flashback's `Files.exists` check (Android scoped storage often returns EACCES for paths such as Download).
- **Sequence export path** — Fixes `path.getParent()` dropping one level too many for image sequences.
- **MediaCodec NDK hardware encoding** — Injects `ndk_codec=1` before the encoder opens, forcing FFmpeg off the default JNI path (which needs `MediaCodec` Java classes in the launcher JVM and crashes the export via `avcodec_open2` when they are missing) onto the pure-native NDK `AMediaCodec_*` path; injected into the javacv recorder on 1.21.x and into FlashbackFFmpegFrameRecorder on 26.x.
- **FFmpeg natives** — Extracts arm64 native libraries at startup for JavaCPP; the jars are not kept in the repo, are resolved from Maven Central per MC version at build time, and must match Flashback's embedded bindings (1.21.x → `ffmpeg 6.1.1-1.5.10`, 26.x → `ffmpeg 8.1.2-1.5.14`), or `avcodec_close()` throws `UnsatisfiedLinkError` on export. Extraction is segmented by natives version (`ffmpeg-natives/<version>/arm64-v8a/`); the two MC versions cannot see each other's extraction root, so leftovers from one version are never loaded by the other.
- **Interface scaling** — A 0.25–4 scale slider in Preferences that applies immediately.
- **imgui B3D rendering** — The Gl3 overlay on 26.1.2 and below renders fully black under MobileGlues; the official 26.2 B3D rendering backend is ported in (including export thumbnails) and packaged only into the 1.21.11 / 26.1.2 jars.
- **Fixes black flicker** — Main render-target rebuilds caused by editor toggles / frame-area changes are deferred until after the current frame is presented.

Settings are stored in `flashback-androidfix.json` in the Flashback config directory.

## Building

Requires JDK 25.

```bash
./gradlew buildAllAndCollect
```

Jars are collected into `build/libs/<mod version>/`, one per supported version (`-MC1.21.1`, `-MC1.21.4`, `-MC1.21.11`, `-MC26.1.2`, `-MC26.2`, `-MC26.3`). Pushing `main` triggers a GitHub Actions build; the artifacts are in that run's `flashback-androidfix` artifact.

The build is based on [Stonecutter](https://codeberg.org/stonecutter/stonecutter): one source tree, one jar per version, with Flashback compile dependencies resolved per MC version at build time.

---

## 中文

面向 [Flashback](https://modrinth.com/mod/flashback) 的安卓修复 mod 能在安卓上正常启动、录制和导出。

### 功能

- **绕过原生对话框** — 视频/截图导出的原生 SDL 对话框在安卓上永不返回，改为直接读写导出设置中配置的文件夹。
- **导出路径持久化** — 路径输入框移入 Preferences，重启后保留；已配置的路径不会被 Flashback 的 `Files.exists` 判定悄悄改回游戏目录（安卓 scoped storage 对 Download 等路径常返回 EACCES）。
- **序列导出路径** — 修正 `path.getParent()` 对图片序列多退一级的问题。
- **mediacodec NDK 硬编** — 编码器打开前注入 `ndk_codec=1`，强制 FFmpeg 从默认的 JNI 路径（需要启动器 JVM 里的 `MediaCodec` Java 类，缺失时 `avcodec_open2` 崩导出）改走 NDK `AMediaCodec_*` 纯 native 路径；1.21.x 注入 javacv recorder、26.x 注入 FlashbackFFmpegFrameRecorder。
- **FFmpeg natives** — 启动时解出 arm64 原生库交给 JavaCPP；jar 不入库，构建时从 Maven Central 按 MC 版本解析，且必须与 Flashback 内嵌绑定一致（1.21.x → `ffmpeg 6.1.1-1.5.10`，26.x → `ffmpeg 8.1.2-1.5.14`），否则导出时 `avcodec_close()` 抛 `UnsatisfiedLinkError`。解压目录按 natives 版本分段（`ffmpeg-natives/<版本>/arm64-v8a/`），两个 MC 版本共享的解压根下互不可见，另一版本的残留文件不会被错误加载。
- **界面缩放** — Preferences 提供 0.25–4 缩放滑块，调整立即生效。
- **imgui B3D 渲染** — 26.1.2 及以下的 Gl3 overlay 在 MobileGlues 下全黑，将官方 26.2 的 B3D 渲染后端移植进来（含导出缩略图），只打进 1.21.11 / 26.1.2 的 jar。
- **修复黑闪** — 编辑器开关/帧区域变化时的主 RT 重建推迟到本帧上屏之后执行。

设置保存在 Flashback 配置目录下的 `flashback-androidfix.json`。

### 构建

需要 JDK 25。

```bash
./gradlew buildAllAndCollect
```

jar 汇总到 `build/libs/<mod version>/`，每个支持版本各一个（`-MC1.21.1`、`-MC1.21.4`、`-MC1.21.11`、`-MC26.1.2`、`-MC26.2`、`-MC26.3`）。推送 `main` 会触发 GitHub Actions 构建，产物在该次运行的 `flashback-androidfix` artifact。

构建基于 [Stonecutter](https://codeberg.org/stonecutter/stonecutter)：一份源码、每版本一个 jar，Flashback 编译依赖按 MC 版本在构建时解析。
