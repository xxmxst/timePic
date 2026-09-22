# TimePic 水印相机

TimePic 包含原生 Android 水印相机和 Windows 离线批量图片水印工具，支持时间、地址、备注和三套模板，处理后的图片另存，原图保持不变。

## 下载 v1.0.0

| 平台 | 下载 | 使用方式 |
| --- | --- | --- |
| Windows 10/11 64 位 | [免安装 ZIP](https://github.com/qq244901796/timePic/releases/download/v1.0.0/TimePic-Desktop-1.0.0-win-x64.zip) | 完整解压后双击 TimePic.exe |
| Android 8.0 及以上 | [安卓测试 APK](https://github.com/qq244901796/timePic/releases/download/v1.0.0/TimePic-Android-1.0.0-debug.apk) | 下载后安装，当前为 Debug 签名测试版 |

[版本说明](https://github.com/qq244901796/timePic/releases/tag/v1.0.0) · [SHA-256 校验文件](https://github.com/qq244901796/timePic/releases/download/v1.0.0/SHA256SUMS.txt)

Windows 11 和 Android 12 已完成实际运行验证，完整范围见两端验证记录。


## Windows 电脑端（免安装）

桌面端位于 [desktop](desktop/README.md)，支持单张或批量选择 JPG、PNG、WebP，手动填写水印时间与地址，逐张覆盖、预览和独立目录输出，原图保持不变。

下载本地构建的 [Windows 64 位免安装 ZIP](https://github.com/qq244901796/timePic/releases/download/v1.0.0/TimePic-Desktop-1.0.0-win-x64.zip)，完整解压后双击 TimePic.exe。使用说明及构建命令见 [桌面端文档](desktop/README.md)，测试结果见 [桌面端验证记录](desktop/docs/verification.md)。

## Android 端使用

原生 Android 端使用 Kotlin、Jetpack Compose 和 CameraX。最低支持 Android 8.0（API 26），目标 API 36，应用包名 `com.timepic.app`。

1. 点击“开启相机”并允许相机权限，或点击“相册”选择一张图片。
2. 点击定位图标获取当前位置；在“地址”中搜索地区、选择候选地点，或直接填写地址文字。
3. 在“时间”中指定日期和时分秒，或恢复实时。固定时间会继续用于后续照片；实时模式在拍摄或导入时确定该张照片的时间。
4. 选择“简洁时间 / 日常打卡 / 工程记录”，修改备注；拖动整块水印调整位置，在“大小”中缩放。
5. 拍摄后进入编辑页，点击“保存到相册”。生成的 JPEG 位于系统相册的 `Pictures/TimePic`，可查看或调用系统分享。

相册图片以原始图像像素为基础追加水印并另存副本。如果原图已经带有水印，原有水印也会保留。编辑时间只控制可见的水印文字。

## 系统能力与权限

- 定位使用 Android `LocationManager` 和 AndroidX 兼容接口；地址搜索、经纬度转地址使用系统 `Geocoder`。无需配置地图 Key，也不依赖 Google Play 服务 SDK。
- 地区选择以“文字搜索 → 地点候选 → 选择”实现。Android 系统不提供应用可直接嵌入的通用地图选点界面。
- 地址搜索的结果和可用性取决于手机系统服务及网络。无结果、服务缺失或超时均可直接填写地址。已获取坐标但解析失败时显示坐标。
- 手动填写或选中的地址不会被后台定位覆盖；只有主动点击“当前位置 / 重新定位”才会替换。
- 相机和定位分别申请权限；拒绝相机权限仍可编辑相册图片，拒绝定位仍可填写地址。地址弹窗提供“定位权限设置”入口。
- 只请求使用期间的定位，不请求后台定位。相册使用系统 Photo Picker，不可用时由 AndroidX 回退到系统文件选择器。
- Android 8–9 保存照片时需要写入存储权限；Android 10 及以上使用 MediaStore 保存。
- 应用没有自建后台、登录或云同步。照片在设备内处理；系统地理编码服务可能联网。

## 构建与安装

环境：JDK 17、Android SDK Platform 36、可访问 Google Maven / Maven Central 的网络。Gradle Wrapper 已包含在项目中，首次构建会下载依赖。

在 Android Studio 打开项目根目录并选择 JDK 17，或使用 PowerShell：

```powershell
cd D:\vscode\timePic
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

如果系统默认 Java 不是 JDK 17，可指定已有 JDK 路径：

```powershell
.\gradlew.bat :app:assembleDebug "-Dorg.gradle.java.home=C:\Path\To\jdk-17"
```

在 `local.properties` 中配置本机 `sdk.dir`；该文件不提交版本控制。示例：

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Debug APK 可直接安装用于验证；对外发布需自行配置正式签名。

工具链固定为 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.10、Compose BOM 2025.08.01、CameraX 1.5.3，避免使用动态版本。

## 实现结构

- `model`：模板、时间模式、地址来源和相对位置等纯 Kotlin 模型。
- `data`：DataStore 配置存储、系统定位与地理编码。定位请求可取消；过期的定位和搜索回调不会覆盖最新选择。
- `imaging`：共享水印绘制器、图片导入、EXIF 方向校正与 MediaStore 导出。
- `ui`：相机预览、图片编辑、时间/地址/备注/大小弹窗，横屏时采用左右布局。
- `TimePicViewModel`：统一管理照片草稿和编辑状态。选图后复制到应用私有目录，减少临时 URI 授权失效的影响，支持 Activity 重建后继续编辑。

相机预览和拍照共享 CameraX ViewPort；水印使用统一的 1000 单位坐标系排版，再按图像尺寸等比绘制。导出使用 JPEG 质量 95；最长边上限 4096 像素，并根据设备内存做采样。原照片不会被覆盖。保存失败保留编辑草稿，清理未完成的输出记录。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

测试只生成和清理自己的测试图片。仪器测试通过应用模型导入自建测试图，并操作真实编辑界面；系统选择器入口和实际拍摄另行在真机手动验证。

详细结果见 [docs/verification.md](docs/verification.md)。本机 Android 16 模拟器因缺少硬件加速驱动无法启动，尚未完成该版本运行验证；Android 8–9 的旧存储分支尚未在设备验证。

## 参考

- [CameraX 拍照](https://developer.android.com/media/camera/camerax/take-photo)
- [Android Geocoder](https://developer.android.com/reference/android/location/Geocoder)
- [系统照片选择器](https://developer.android.com/training/data-storage/shared/photo-picker)
- [MediaStore 共享图片存储](https://developer.android.com/training/data-storage/shared/media)
