# TimePic 桌面版

Windows 10/11 x64 的离线图片水印工具。在一个窗口内选图、手动填写时间和地址、预览并批量另存，原图保持不变。

界面截图见 [运行截图](docs/editor.png)，实际验证结果见 [验证记录](docs/verification.md)。

## 直接使用

下载 [Windows 免安装 ZIP](https://github.com/qq244901796/timePic/releases/download/v1.0.0/TimePic-Desktop-1.0.0-win-x64.zip)，完整解压后双击 `TimePic.exe`。同目录的资源、DLL 和语言文件需一并保留。用户电脑不需要安装 Node.js、Java、Python 或额外浏览器组件。

1. 点击“选择图片”或“＋”，添加单张或多张 JPG、PNG、WebP 静态图片。支持追加、去重、勾选和移除。
2. 选择简洁时间、日常打卡或工程记录模板，填写固定时间、地址和可选备注。
3. 需要单独设置时，点击左侧图片，再切换“当前图片”，勾选单独时间或地址。取消勾选或点击“恢复批量设置”即可重新跟随公共值。
4. 通过大小滑块、四角按钮或直接拖动预览水印调整整批样式。
5. 选择独立的输出目录，点击“导出选中图片”。完成后可打开输出目录。

地址、备注可留空；日期必须有效。模板、备注、大小、位置整批共用，时间和地址允许逐图覆盖。打开软件时预填一次当前时间，之后不会走时，也不会读取 EXIF 时间代替手动输入。

## 文件与输出规则

- 原图只读。输出目录不能是任何已添加原图的所在目录，可使用原目录下的新子文件夹；目录别名和 Windows junction 也会检查。
- 输出名为 `原名_watermark.原扩展名`，同名自动使用 `_watermark_2`、`_watermark_3`，并发新建也不覆盖既有文件。
- 保持格式和 EXIF 方向校正后的像素尺寸，不自动缩小。JPG/WebP 质量为 95%，PNG 无损编码；PNG/WebP 保留透明背景。
- 输出经过重新编码，不复制原图的 EXIF、GPS、相机信息等元数据。新水印不能消除图片上已经存在的水印。
- 支持静态 JPG/JPEG、PNG、WebP；不支持 HEIC、GIF、动画 PNG/WebP。单张输入上限 512 MB、1.2 亿像素、单边 32767 像素，超限明确失败。
- 导出固定本次设置快照，逐张处理并释放内存。单张失败继续处理其余图片；取消在当前图片完成后生效，成功文件保留，正常写入失败会清理未完成文件。
- 常用样式与上次输出目录写入当前用户应用数据目录 `timepic-desktop/settings.json`；不保存输入图片列表或逐图覆盖。

## 开发与构建

需要 Windows x64 和 Node.js 22.12 以上（本机使用 Node.js 24.13.1）。

```powershell
cd D:\vscode\timePic\desktop
npm ci
npm run dev
```

```powershell
npm run build       # 类型检查、页面构建和主进程构建
npm start           # 运行构建后的程序
npm run pack        # 生成 release/win-unpacked/TimePic.exe 及运行文件
npm run dist        # 生成完整 Windows ZIP 包
```

首次构建需要联网下载依赖和 Electron，运行成品不需要网络。打包脚本使用 electron-builder 生成程序目录，再调用 Windows 自带 Compress-Archive 生成 ZIP，并对临时文件占用进行有限重试。依赖版本和完整性由 `package-lock.json` 锁定。ZIP 未进行商业代码签名。

## 验证

```powershell
npm test
# 界面测试的样图由 Pillow 生成；Pillow 仅是生成测试素材的开发依赖。
python -m pip install Pillow
python scripts/create-assets.py
npm run test:e2e
# 生成 ZIP 后，解压到独立目录并测试最终 EXE
node scripts/verify-package.mjs
```

核心测试覆盖原图哈希、目录别名拦截、同名并发输出、失败清理、日期校验和覆盖继承。Playwright 启动真实 Electron 窗口，覆盖预览与 PNG 输出像素相等、EXIF 八方向、透明区域、逐张设置、错误隔离、百张处理、取消重试和最小窗口布局。测试仅替换原生选图对话框返回值和特定文件系统故障，图片解码、绘制、编码、IPC 和实际文件写入均运行生产代码。

测试产物在 `test-results/`，报告在 `playwright-report/`。打包验证可设置 `TIMEPIC_PACKAGED_EXE` 为解压出的 EXE 路径后运行 Playwright；使用独立用户配置目录。

## 实现说明

- `electron/`：窗口、原生文件选择、受限 IPC、源文件登记、独立目录检查和排他输出。
- `src/`：TypeScript 页面状态、逐图覆盖、共享的 1000 单位水印布局、Worker 图片队列。
- 主窗口开启 context isolation、sandbox，禁用页面 Node.js；本地自定义协议与 CSP 限制页面资源，preload 仅暴露具体业务接口。
- 预览与导出使用同一套 Canvas 绘制函数；预览只缩小显示，导出使用方向校正后的完整像素。
- 中文字体 Noto Sans SC 随包提供，授权见 `public/fonts/OFL.txt`。应用图标和测试场景由项目脚本绘制。
- 安卓工程保持独立，桌面端未引入定位、地图、账号或后端服务。

技术资料：[Electron 安全配置](https://www.electronjs.org/docs/latest/tutorial/security)、[electron-builder Windows 构建](https://www.electron.build/win.html)、[OffscreenCanvas 导出](https://developer.mozilla.org/en-US/docs/Web/API/OffscreenCanvas/convertToBlob)。
