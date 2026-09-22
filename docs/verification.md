# 验证记录

日期：2026-09-22。

## 构建环境和交付物

- Windows / JDK 17.0.14 / Android SDK 36。
- AGP 8.13.2、Gradle 8.13、Kotlin 2.2.10。
- 应用：`com.timepic.app`，版本 `1.0.0`，minSdk 26 / targetSdk 36。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，21,155,366 字节（约 20.18 MiB）。
- 最终 Debug APK 已安装到连接的华为 NOH-AN00 真机。

## 自动检查

最终执行（本机 JDK 路径已替换为通用示例）：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest "-Dorg.gradle.java.home=C:\Path\To\jdk-17" --console=plain
```

结果：**BUILD SUCCESSFUL**。

| 检查 | 结果 |
| --- | --- |
| Debug APK 构建 | 通过 |
| JVM 单元测试 | 6 项通过，0 失败 |
| 华为 NOH-AN00 / Android 12 / API 31 仪器测试 | 4 项通过，0 失败 |
| Android Lint | 0 错误，15 条警告 |

Lint 警告主要是依赖存在更新版本、KTX 写法建议，以及 minSdk 与资源目录版本标记重复；没有禁用或忽略检查来跳过错误。

单元测试覆盖固定时间不递增、实时拍摄/导入时刻、手动地址清除不相关坐标、水印边缘约束、跨分辨率位置一致性，以及闰日与时区格式化。

仪器测试覆盖：

1. EXIF 1–8 全部旋转和镜像方向，用四色测试图断言实际像素方向。
2. 三套模板在横图、竖图和超宽图上的边界与等比位置，包含长地址和备注。
3. 导入后合成 JPEG、可正常解码、确有水印像素，并确认原始文件字节完全不变。
4. 通过界面填写地址、固定时间、选择模板、填写备注、导入自建图片、拖动水印、Activity 重建、横竖屏切换、保存到相册并返回相机。

仪器测试中的导入使用应用模型传入自建图片 URI；系统选择器单独按下表验证。测试会清理自己的临时图和 MediaStore 输出。

报告位置：

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`

## 真机交互验证

设备为华为 NOH-AN00，系统报告 Android 12 / API 31。

| 场景 | 结果 |
| --- | --- |
| 首次打开，未授权相机 | 显示开启相机和权限设置入口，相册入口可用 |
| 授予相机权限后拍摄 | 实际后置拍摄成功并进入编辑页 |
| 前后摄像头切换 | 前置拍摄成功，可放弃草稿并返回相机 |
| 拒绝定位权限 | 显示提示，仍可拍照和编辑 |
| 获取当前位置 | 系统成功返回位置，并解析出中文地址 |
| 系统地区搜索 | 输入 Beijing，返回“北京市”候选，选择后应用到水印设置 |
| 手动填写地址、时间与备注 | 通过仪器测试操作真实弹窗验证 |
| 保存实际拍摄的照片 | 显示“已保存到系统相册”，系统选择器中能找到生成的 TimePic JPEG |
| 系统相册选择器 | 在 Android 12 上使用系统文件选择器，选择生成的照片后显示“相册照片”并进入编辑页 |
| 界面视觉检查 | 已检查首页和真实拍照编辑页截图，水印位于图像范围内，保存按钮可见 |

## 尚未完成的环境验证

- 本机存在 Android 16 / API 36.1 模拟器镜像，但启动失败：缺少 Android Emulator 硬件加速驱动。未修改系统虚拟化配置或安装内核驱动，因此尚未完成 API 36 设备运行验证。
- 无 Android 8–9 测试设备，旧版存储权限和保存路径分支仅完成编译及静态检查。
- 定位关闭、仅大致位置、系统 Geocoder 缺失、断网和存储耗尽等分支已实现处理，尚未逐项在真机强制制造这些条件。
- 尚未执行正式签名的发布包测试。当前交付物为可安装的 Debug APK。
