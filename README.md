# Screenshot to Your Pad to Do Note

一个“电脑截图直传平板相册 + 平板悬浮遥控电脑视频”的双端工具。

- Windows 端：Tauri + React + Rust，负责截图、局域网 WebSocket 服务、视频快捷键模拟。
- 小米/安卓平板端：Kotlin + Compose，负责扫码配对、悬浮遥控窗、接收截图并保存到系统相册。

## 当前 V1 能力

- Windows 端显示配对二维码、配对码、局域网地址和已配对设备。
- 平板端扫码配对，保存 token，后续可自动重连。
- 平板悬浮窗使用图标按钮：区域截图、快速截全屏、后退 5 秒、暂停/播放、快进 5 秒，并可收起成 1.5 倍大悬浮球。
- 展开悬浮窗显示已连接、连接中或已断开状态，悬浮球通过同色圆点显示连接状态；前台连接服务会心跳检测并自动重连。
- 暂停/播放命令经电脑确认执行后，悬浮窗按钮会在暂停和播放图标之间切换。
- 悬浮球只有靠近屏幕左右边缘时才会半掩吸附；吸附后仍可直接拖动，点一下可展开。
- 悬浮球在左半屏向右展开，在右半屏向左展开；球体完全越过屏幕中线才切换方向，压住中线时沿用上一次方向。
- 平板点区域截图图标后会出现全屏选区层，拖出矩形区域后可以整体移动或从四角调整；电脑端同步显示鼠标穿透、不抢焦点的绿色细框。
- 平板远程选区只抓取一次 Windows 主屏原始像素，不生成无用预览；确认时后台裁剪并发送无损 PNG，电脑不会进入全屏或改变当前画面。
- 选区层支持取消、重新划区和保存；拖动更新最多每 16ms 合并一次，避免消息积压。
- 全屏截图使用全分辨率 JPEG 90，区域截图保持无损 PNG；两者均通过 WebSocket 二进制帧直接传输，避免 Base64 和大 JSON 的额外开销。
- 全屏和区域截图都会立即在电脑、平板显示处理中状态；只有平板完成 SHA-256 校验和图库写入后，两端才显示“截图已保存到平板”。失败或 15 秒无回执会显示错误，不会误报成功。
- Windows 端 `Ctrl+Alt+A` 或主界面按钮会打开选区截图预览，松开鼠标后发送选区截图。
- 关闭 Windows 主窗口时程序会隐藏到系统托盘并继续在后台运行；点击托盘图标可恢复，托盘菜单“退出”才会结束服务。
- 图片写入安卓系统相册：`Pictures/PC Screenshots`。

## 快速使用

### 发布版安装

普通用户不需要安装开发环境。进入 [GitHub Releases v0.1.4](https://github.com/OZ-winner/Screenshot-to-your-pad-to-do-note/releases/tag/v0.1.4) 下载同一版本的两个文件：

- Windows 电脑端：`ScreenshotToPad-Windows-0.1.4-x64-setup.exe`
- Android 平板端：`ScreenshotToPad-Android-0.1.4-debug.apk`
- 校验文件：`SHA256SUMS.txt`

安装后让电脑和平板连接同一个 Wi-Fi，先启动 Windows 端，再打开平板 App 扫码配对。首次使用需要在 Windows 防火墙中允许专用网络通信，并在平板系统设置里允许悬浮窗权限。

### 1. 启动 Windows 端

发布版用户双击安装 `ScreenshotToPad-Windows-0.1.4-x64-setup.exe`，安装完成后启动“截图直传”。

在项目根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\dev-windows.ps1
```

`启动截图直传-Windows端.bat` 是开发入口，会检查依赖并按需重新构建。日常使用请安装发布版并使用安装器创建的“截图直传”快捷方式；`scripts\create-desktop-shortcut.ps1` 也只会指向已安装或已构建的正式 EXE，不会启动开发构建。

首次启动时，如果 Windows 防火墙弹窗，请允许专用网络通信。主界面会显示局域网地址、配对码和二维码。

点击主窗口右上角关闭按钮会隐藏到系统托盘，平板连接和快捷键仍可继续使用。点击托盘图标恢复窗口；需要完全退出时，在托盘菜单选择“退出”。

正式版不会显示控制台窗口，并且只允许一个实例运行。重复点击快捷方式会直接显示已经在后台运行的主窗口。

### 2. 安装 Android 平板端

当前 debug APK 构建后位于：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

如果平板已开启 USB 调试并连接电脑，可以运行：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\android\app\build\outputs\apk\debug\app-debug.apk"
```

安装后，平板上打开“截图直传”。

### 3. 配对和悬浮窗

1. Windows 端保持运行。
2. 平板 App 点“扫码”，扫描 Windows 端二维码。
3. 点“配对连接”。
4. 点“悬浮窗授权”，在系统设置里允许悬浮窗。
5. 回到 App，点“打开悬浮窗”。

悬浮窗按钮：

- 左侧 `≡` 手柄：按住拖动，可以移动整个悬浮窗。
- 截图框图标：在平板上拖出长方形区域，电脑同步显示选区，点对勾后截图传到平板相册。
- 全屏截图图标：不打开选区，直接截取电脑主屏并保存到平板相册。
- 后退图标：让电脑当前视频后退 5 秒。
- 暂停/播放图标：控制当前视频；电脑确认命令执行后图标同步切换。
- 快进图标：让电脑当前视频快进 5 秒。
- 收起图标：把遥控条收缩成悬浮球；悬浮球靠近左右边缘松手时半掩吸附，点一下可重新展开。

也可以在平板 App 主界面点“截图”，会打开同一个选区截图流程。

## Windows 开发配置

前置环境：

- Node.js / npm
- Rust / Cargo
- Windows 10+ 或 Windows 11
- Visual Studio C++ Build Tools
- WebView2 Runtime

命令：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-windows.ps1
powershell -ExecutionPolicy Bypass -File scripts\dev-windows.ps1
```

`dev-windows.ps1` 会自动加载 Visual Studio C++ 编译环境。如果第一次运行时 Windows 防火墙弹窗，请允许专用网络通信。

打包 Windows 安装器：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

安装包输出位置：

```text
src-tauri\target\release\bundle\nsis\截图直传_0.1.4_x64-setup.exe
```

## Android 开发配置

前置：

- Android Studio
- Android Studio 自带 JBR/JDK
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- Android SDK Platform-Tools
- FlClash 或其他可访问 Maven 仓库的代理

本项目已在 `android/gradle.properties` 中配置 Gradle 使用 FlClash 默认代理：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

如果你的代理端口不是 `7890`，请改成实际端口。

构建 debug APK：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

离线运行截图二进制协议检查：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-android-protocol.ps1
```

检查 Android 依赖网络：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-android-network.ps1
```

## 常见问题

### Gradle 下载超时

项目已将 Gradle 分发包地址配置为镜像：

```text
https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.10.2-bin.zip
```

如果仍下载失败，请打开代理或换网络。

### 找不到 `com.android.application`

说明 Gradle 连不到 Maven 仓库。确认 FlClash 已启动，并且 `127.0.0.1:7890` 正在监听。

### SDK 目录不可写

如果出现类似：

```text
The SDK directory is not writable
platforms;android-36
```

请在 Android Studio 中打开：

```text
File -> Settings -> Languages & Frameworks -> Android SDK
```

在 `SDK Platforms` 勾选 `Android 16.0 / API 36`，在 `SDK Tools` 勾选 `Android SDK Build-Tools` 和 `Android SDK Platform-Tools`。

### JVM target 不一致

项目已将 Java/Kotlin 统一为 JVM 17 输出。如果仍遇到 JVM target 错误，请重新拉取最新代码并重新构建。

## WebSocket 协议

客户端配对：

```json
{
  "type": "pair",
  "code": "ABC123",
  "device_name": "小米平板"
}
```

客户端命令：

```json
{
  "type": "command",
  "command": "screenshot",
  "token": "paired-token"
}
```

可用命令：

- `screenshot`
- `play_pause`
- `seek_back_5`
- `seek_forward_5`

平板远程选区：

```json
{
  "type": "remote_selection",
  "token": "paired-token",
  "phase": "begin",
  "x_ratio": 0.12,
  "y_ratio": 0.18,
  "width_ratio": 0.5,
  "height_ratio": 0.35
}
```

`phase` 可用值：

- `begin`
- `update`
- `confirm`
- `cancel`

服务端先发送截图元数据文本帧：

```json
{
  "type": "screenshot_meta",
  "id": "...",
  "filename": "PC_20260804_120000.jpg",
  "created_at": "...",
  "width": 1920,
  "height": 1080,
  "mime_type": "image/jpeg",
  "byte_length": 183421,
  "sha256": "..."
}
```

紧接着发送一条 WebSocket 二进制帧，内容为 JPEG 或 PNG 原始字节。全屏截图为 `.jpg` / `image/jpeg`，区域截图为 `.png` / `image/png`。元数据与二进制帧必须按顺序成对发送。

平板完成校验和图库写入后返回：

```json
{
  "type": "screenshot_result",
  "token": "paired-token",
  "id": "...",
  "success": true,
  "message": null
}
```

## V1 限制

- 选区截图目前基于主屏截图预览，尚未做完整多显示器虚拟桌面选区。
- 平板快速全屏截图和远程选区截图目前都基于 Windows 主显示器。
- 视频控制通过模拟当前活动窗口快捷键实现，默认适配多数浏览器/播放器。
- 如果 Windows 防火墙提示网络访问，需要允许专用网络通信。
