# 开发与项目结构

[返回项目首页](../README.md)

## 开发环境

已验证的开发环境为 JDK 17、Android SDK 36 和 Build Tools 36.0.0。应用最低支持 Android 6.0（API 23），目标版本为 Android 16（API 36）。

## 构建与测试

运行单元测试、Release Lint，并生成 Debug APK、Release APK 和 Release AAB：

```bash
./gradlew test lintRelease assembleDebug assembleRelease bundleRelease
```

主要构建产物：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 已签名 Release APK：`app/build/outputs/apk/release/app-release.apk`
- 未签名 Release APK：`app/build/outputs/apk/release/app-release-unsigned.apk`
- Release AAB：`app/build/outputs/bundle/release/app-release.aab`
- R8 映射文件：`app/build/outputs/mapping/release/mapping.txt`

Release 构建会开启 R8 压缩、优化、混淆和资源收缩。未配置签名时，Gradle 会生成未签名的 Release APK / AAB，CI 不需要签名密钥。

## Release 签名

签名只从以下环境变量读取，四个变量必须同时设置或同时缺省；只配置一部分会在 Gradle 配置阶段失败：

- `TIEBAPURE_ANDROID_KEYSTORE`：keystore 路径
- `TIEBAPURE_ANDROID_STORE_PASSWORD`：keystore 密码
- `TIEBAPURE_ANDROID_KEY_ALIAS`：密钥别名
- `TIEBAPURE_ANDROID_KEY_PASSWORD`：密钥密码

```bash
export TIEBAPURE_ANDROID_KEYSTORE="$HOME/.android/tiebapure-release.jks"
export TIEBAPURE_ANDROID_STORE_PASSWORD='<store password>'
export TIEBAPURE_ANDROID_KEY_ALIAS='tiebapure'
export TIEBAPURE_ANDROID_KEY_PASSWORD='<key password>'
./gradlew assembleRelease bundleRelease
```

签名密钥必须长期安全保存。Android 只允许使用相同签名证书的 APK 覆盖更新现有安装。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | 应用容器、导航、自适应窗口布局和依赖组装；连接各功能模块与仓库实现 |
| `core:model` | 与平台无关的领域模型、内容提交约束、阅读偏好和过滤策略 |
| `core:protocol` | 从 `proto/` 生成的 lite Protobuf Java / Kotlin 代码 |
| `core:network` | HTTPS 传输、请求签名、协议映射和贴吧读写接口 |
| `core:data` | Room、DataStore、加密账号存储、本地记录和网络仓库 |
| `core:designsystem` | Compose 主题、通用阅读状态和可复用内容组件 |
| `core:media` | 图片浏览与保存、远程媒体加载、离线媒体策略、视频和语音播放 |
| `core:testing` | 不依赖真实账号和网络的共享测试 fixture |
| `feature:home` | 首页推荐列表 |
| `feature:forum` | 进吧、贴吧列表、吧内帖子与排序 |
| `feature:search` | 全局搜索与吧内搜索 |
| `feature:thread` | 帖子详情、楼层、楼中楼和富文本展示 |
| `feature:account` | 登录、个人主页、消息、关注关系、收藏和浏览历史 |
| `feature:composer` | 发帖与回复编辑器、图片和贴吧表情 |
| `feature:settings` | 外观、阅读、屏蔽、签到与实验性写操作设置 |

顶层 `app` 负责把 `feature:*` 与 `core:*` 组合成完整应用。功能模块通过领域模型和仓库接口表达需求，网络协议、持久化与媒体实现保留在对应的 `core` 模块中。

## Protobuf 协议

`proto/` 保存应用自行维护的最小贴吧协议定义，只包含实际发送或读取的字段。Android Gradle Protobuf 任务从这里生成 `core:protocol` 的 lite 代码；iOS 客户端在 [TiebaPure-iOS](https://github.com/infinityf4p/TiebaPure-iOS) 中维护并生成对应的 Swift 产物。

字段编号、wire 类型以及 `optional` / `repeated` 语义都是协议的一部分。修改它们时必须同步增加手写 wire fixture，并验证请求编码、响应解码和领域模型映射，不能只使用同一份 schema 做编码后再解码测试。更详细的约束见 [`proto/README.md`](../proto/README.md)。
