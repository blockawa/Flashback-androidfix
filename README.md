# Flashback Android Fix

面向 Android 的 Fabric 客户端 mod，让 [Flashback](https://modrinth.com/mod/flashback) 能在安卓上正常录制和导出。

## 模组作用

- **导出卡死** — Flashback 每次导出都会弹原生 SDL 保存/文件夹对话框，在安卓上永不返回。改为直接写入导出设置里配置的
  文件夹（`internalExport.defaultExportPath`），路径规则沿用 ReplayMod 的约定：相对路径基于游戏目录，绝对路径原样使用。

- **导出路径变为持久设置** — 路径输入框放在 Preferences 窗口的 *Exporting* 小节之后，而不是每次导出时出现，
  重启后依然保留。配置过的路径一律沿用、绝不会被改回：Flashback 原本在 `getDefaultFilename` 里只要
  `Files.exists` 判定文件夹"不存在"就把 `defaultExportPath` 重置成游戏目录，而安卓 scoped storage 对
  `/storage/emulated/0/Download` 这类路径经常返回 EACCES、让该判定为假——这里直接忽略这次判定，路径写不进去
  时导出报错，而不是悄悄换个地方。

- **导出** — 与视频导出同样绕过原生对话框，直接写入配置的文件夹。

- **导出序列路径** — `createExportSettings` 会把 `path.getParent()` 存成默认导出路径，对图片序列来说多退了一级；
  本 mod 恢复成真正被选中的那个文件夹。

- **`mediacodec` 编码器失效** — FFmpeg 探针可能走 NDK `AMediaCodec_*` 路径探到成功，真正导出时却因 JVM 缺少
  `android.media.MediaCodec` 而报 `avcodec_open2() error -542398533`。这类编码器从列表中剔除，导出回退到内置的
  `libopenh264`。

- **缺失的 natives** — 启动时解出 FFmpeg / JavaCPP / ImGui `arm64-v8a` 原生库，并通过 `Loader.findLibrary`
  交给 JavaCPP，使 `javacv-platform` 取到它们而不是去找桌面平台的库。FFmpeg / JavaCPP 的 jar 不入库，构建时
  从 Maven Central（`repo1.maven.org/maven2/org/bytedeco/`）按 Minecraft 版本解析：**版本必须与 Flashback
  内嵌的 javacpp 绑定一致**——1.21.11 的 Flashback 内嵌 `ffmpeg 6.1.1-1.5.10` + `javacpp 1.5.10`，26.x 内嵌
  `ffmpeg 8.1.2-1.5.14` + `javacpp 1.5.14`。绑定了 6.1.1 却加载 8.1.2 的 `libavcodec.so` 会在打开导出窗口、
  枚举编码器时因 `avcodec_close()` 等符号不存在而抛 `UnsatisfiedLinkError`（导出崩溃）；ImGui 的 natives
  仍随仓库放在 `deps/`。

- **界面缩放** — Preferences 窗口提供 *界面大小* 滑块（0.25 - 4），拖动改值后点 *重新应用* 才生效并重建字体。

- **imgui 界面在 MobileGlues 渲染器下全黑** — Flashback 26.1.2 及以下用旧的 Gl3 后端绘制 overlay，
  Gl3 绘制时会无条件把帧缓冲绑回 FBO 0，MobileGlues 下整条 immediate-mode 路径都画不出来（26.2 起官方改用
  B3D 后端已修复）。本 mod 把官方 26.2 的 B3D 渲染后端移植进来：`CustomImGuiImplGl3` 的四个调用
  （`init` / `newFrame` / `updateFontsTexture` / `renderDrawData`）全部转交给自建的 `ImGuiB3DRenderer`，
  通过 `imgui_b3d` RenderPipeline 渲染到独立离屏 target，present 之前把裁剪后的游戏画面与 overlay 合成再一起
  上屏；此外 `ExportDoneWindow` 的缩略图 id 也按 B3D 的 1-based 纹理注册表重写。1.21.11 与 26.1.2 用同一套
  方案，只有 1.21.11 的 blaze3d API 差异做了适配：pipeline 用 `withBlend` + `withDepthTestFunction`
  （26.x 的 `ColorTargetState` / `withDepthStencilState` 尚不存在）、顶点元素按
  `register(id, index, Type, Usage, count)` 注册 2-float Position、深度范围固定取 GL 的 `[-1, 1]`
  （26.x 的 `device.isZZeroToOne()` 尚不存在）、着色器写 `#version 150`（对齐 1.21.11 自带 core shader，
  不用 `layout(location=...)`，属性由 `GlProgram` 按顶点格式名字绑定）、合成驱动点仍是
  `Minecraft.runTick`（1.21.11 没有 `renderFrame`）。修复只打进 1.21.11 与 26.1.2 的 jar——代码放在
  `versions/<mc>/` 下按版本自动识别，26.2/26.3 的 jar 完全不含。

设置保存在 Flashback 配置目录下的 `flashback-androidfix.json`。

## 如何构建

需要 JDK 25（1.21.11 目标按 Java 21 编译，26.x 目标按 Java 25 编译）。

```bash
./gradlew buildAllAndCollect
```

jar 会汇总到 `build/libs/<mod version>/`，每个支持的 Minecraft 版本各一个
（`-MC1.21.11`、`-MC26.1.2`、`-MC26.2`、`-MC26.3`）。
推送到 `main` 会触发 GitHub Actions 跑同样的构建，产物以 `flashback-androidfix` artifact 挂在该次运行上。

构建基于 [Stonecutter](https://codeberg.org/stonecutter/stonecutter) 多版本机制：源码一份、每个版本一个 jar，
Flashback 编译依赖在构建时按 Minecraft 版本解析。1.21.11 是最后一个混淆版本，该目标启用
官方 Mojang 映射并产出 `remapJar`；26.x 起 Minecraft 不再混淆，不需要映射。
