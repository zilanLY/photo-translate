# Photo Translate

Android 端侧（离线）**拍照/实时 OCR 翻译**应用。CameraX 取流 + ML Kit Text Recognition v2（拉丁 + 中日韩双识别器）+ ML Kit Translate 离线翻译，全程无需联网上传图片。

## 功能

- 📷 **实时模式**：相机预览逐帧 OCR + 翻译，节流 250ms、仅处理最新帧，结果不跳动
- 📸 **拍照模式**：拍照 → 方向校正 → 降采样 → OCR → 翻译 → 结果页展示
- 🈶 **中日韩优先**：拉丁 + CJK 双识别器并行识别、按版面合并，支持竖排/多栏阅读顺序
- 🌐 **语种自动检测**：OCR 文本块语种加权推断 + Language Identification 兜底，BCP-47 归一化（zh-Hans→zh）
- 💾 **模型预热**：进入相机页后台预下载常用翻译模型，首次翻译不再卡顿
- 📜 **历史记录**：Room 持久化；点击对焦、复制/分享结果

## 技术栈

| 组件 | 版本 |
|------|------|
| AGP / Kotlin / Gradle | 8.3.2 / 1.9.22 / 8.5 |
| 编译 SDK / minSdk / JDK | 34 / 21 / 17 |
| CameraX | 1.3.4 |
| ML Kit text-recognition (+chinese) | 16.0.0 |
| ML Kit translate | 17.0.3（勿降级，见 HANDOVER.md 轮次 A） |
| Room / Coroutines | 2.6.1 / 1.7.3 |

依赖注入为手动构造器注入（无 Hilt）。

## 构建

**推荐直接用 GitHub Actions**（push 到 master 自动构建，打 `v*` tag 自动发布 Release）：

- 签名密钥通过仓库 Secrets 注入：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`
- 无 Secrets 时产出未签名 release APK

本地构建：复制 `local.properties.template` 为 `local.properties` 并填 `sdk.dir`，然后 `./gradlew :app:assembleRelease`（需准备签名文件）。

## 目录

```
app/src/main/kotlin/com/example/phototranslate/
├── application/   # 全局单例：双 OCR 识别器
├── domain/        # 数据模型 + LangUtil(BCP-47归一化) + ReadingOrder(版面阅读顺序)
├── repository/    # OCR 双识别器合并 / 翻译缓存与预热 / 历史
├── usecase/       # 用例层
└── ui/            # camera(主页) / result / history / language / settings
```

更多历史修复背景与硬约束见根目录 `HANDOVER.md`。
