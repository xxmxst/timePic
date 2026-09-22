# TimePic 桌面版验证记录

验证时间：2026-09-22（北京时间）。实测系统：Windows 11 x64，系统版本 10.0.26200。

## 交付物

- 免安装包：[TimePic-Desktop-1.0.0-win-x64.zip](https://github.com/qq244901796/timePic/releases/download/v1.0.0/TimePic-Desktop-1.0.0-win-x64.zip)
- 文件大小：152,874,084 字节（约 145.8 MiB）
- SHA-256：`0c3f4beaccfe14ecf2a0d069261448c5af8668126707ff4240871f5eb507a4d0`
- 完整解压后双击 `TimePic.exe`，保留同目录的运行文件。
- 运行截图：[editor.png](editor.png)。

## 实际通过的检查

| 检查 | 结果 |
| --- | --- |
| TypeScript 类型检查和 Vite / Electron 构建 | 通过 |
| 核心逻辑及文件系统测试 | 10/10 通过 |
| EXE 临时占用有限重试测试 | 3/3 通过 |
| 开发构建真实窗口测试 | 7/7 通过 |
| 最终 ZIP 解压后真实 EXE 测试 | 7/7 通过，23.4 秒 |

最终包解压在含中文和空格的独立目录，工作目录设为解压目录。子进程 PATH 仅包含 Windows System32，NODE_PATH 为空；确认 `app.isPackaged` 为 true，页面和资源加载自解压包内的 `app.asar`。测试阻断 HTTP、HTTPS、WebSocket 外部请求。

真实 EXE 测试覆盖：
- 单张导入、三套模板、手动日期时间、中文地址和备注、拖动及四角位置调整。
- PNG 预览与完整输出逐像素哈希相等，重复导出自动编号。
- 多张追加去重、时间和地址独立覆盖、恢复公共设置、仅导出勾选图片、移除选中项。
- PNG / WebP 透明背景、JPEG EXIF 八种方向、横幅图片与 1×1 极小图片。
- 损坏图片、动画 PNG / WebP 单项失败后继续处理其余图片。
- 原图目录拒绝作为输出目录；模拟写权限拒绝后没有残留半成品。
- 百张图片导出、取消、完整重试；源文件导出前后 SHA-256 保持一致。
- 长中文自动换行、水印不越界、1040×720 最小窗口下主要操作可见。
- 原图在导入后被删除，单张失败且其余图片继续导出。

## 打包恢复机制

Electron 使用锁定版本的已安装运行文件。针对输出 EXE 的打开占用，最多按 1、2、4、8 秒重试，部分写入或其他错误不重试。实际构建中重试后成功写入 ASAR 完整性信息、图标和版本信息。

ZIP 使用 Windows 自带 Compress-Archive，输入暂时被占用时同样有限重试。未修改 electron-builder 依赖文件，也未关闭运行时沙箱或完整性信息。

## 复现

```powershell
npm ci
npm test
python scripts/create-assets.py
npm run test:e2e
npm run dist
node scripts/verify-package.mjs
```

测试素材生成脚本需要 Pillow；运行软件和正常构建无需 Python。打包验证原始记录位于 `test-results/package-verification.json`，截图来自真实运行窗口。

## 验证边界

- 兼容目标是 Windows 10/11 x64，Windows 10 尚未在独立设备实测。
- 文件对话框测试替换了原生对话框返回值；图片读取、绘制、编码、IPC 和磁盘输出使用实际程序代码。
- 写权限错误采用受控故障注入，没有实际填满磁盘。
- 安装包没有商业代码签名；所有功能在本机离线运行。
