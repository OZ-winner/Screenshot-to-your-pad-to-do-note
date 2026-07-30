# Screenshot to Your Pad to Do Note

一个“电脑截图直传平板相册 + 平板悬浮遥控电脑视频”的双端工具。

- Windows 端：Tauri + React + Rust，负责截图、局域网 WebSocket 服务、视频快捷键模拟。
- 小米/安卓平板端：Kotlin + Compose，负责扫码配对、悬浮遥控窗、接收截图并保存到系统相册。

## 当前 V1 能力

- Windows 端显示配对二维码、配对码、局域网地址和已配对设备。
- 平板端扫码配对，保存 token，后续可自动重连。
- 平板悬浮窗包含：截图、后退 5 秒、暂停/播放、快进 5 秒。
- 平板发送截图命令时，Windows 端截取主屏全屏并发送到平板相册。
- Windows 端 `Ctrl+Alt+A` 或主界面按钮会打开选区截图预览，松开鼠标后发送选区截图。
- 图片写入安卓系统相册：`Pictures/PC Screenshots`。

## 快速使用

### 发布版安装

普通用户不需要安装开发环境。进入 GitHub Releases 下载同一版本的两个文件：

- Windows 电脑端：`ScreenshotToPad-Windows-0.1.0-x64-setup.exe`
- Android 平板端：`ScreenshotToPad-Android-0.1.0-debug.apk`

安装后让电脑和平板连接同一个 Wi-Fi，先启动 Windows 端，再打开平板 App 扫码配对。首次使用需要在 Windows 防火墙中允许专用网络通信，并在平板系统设置里允许悬浮窗权限。

### 1. 启动 Windows 端

在项目根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\dev-windows.ps1
```

也可以双击项目里的 `截图直传 Windows端.lnk`，或运行 `创建桌面快捷方式.bat` 后使用桌面快捷方式。

首次启动时，如果 Windows 防火墙弹窗，请允许专用网络通信。主界面会显示局域网地址、配对码和二维码。

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

- `截`：让电脑截图并传到平板相册。
- `退`：让电脑当前视频后退 5 秒。
- `停`：暂停/播放当前视频。
- `进`：让电脑当前视频快进 5 秒。

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

服务端截图：

```json
{
  "type": "screenshot",
  "id": "...",
  "filename": "PC_20260730_120000.png",
  "created_at": "...",
  "width": 1920,
  "height": 1080,
  "sha256": "...",
  "png_base64": "..."
}
```

## V1 限制

- 选区截图目前基于主屏截图预览，尚未做完整多显示器虚拟桌面选区。
- 平板一键截图默认直接截主屏全屏，避免远程触发后还要回电脑确认。
- 视频控制通过模拟当前活动窗口快捷键实现，默认适配多数浏览器/播放器。
- 如果 Windows 防火墙提示网络访问，需要允许专用网络通信。
