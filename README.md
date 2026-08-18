# TiebaPure-Android

[![Android CI](https://github.com/infinityf4p/TiebaPure-Android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/infinityf4p/TiebaPure-Android/actions/workflows/ci.yml?query=branch%3Amain)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)

基于 Kotlin 与 Jetpack Compose 的第三方百度贴吧客户端。与 [TiebaPure-iOS](https://github.com/infinityf4p/TiebaPure-iOS) 使用同一套最小 Protobuf 协议定义，导航、返回手势、媒体播放、存储和自适应布局遵循 Android 平台惯例。

## 功能

**浏览**（无需登录）

- 首页推荐、进吧、吧内列表，「最新 / 精华」分类，可按回复时间或发帖时间排序
- 搜索主题与回复，吧内搜索
- 帖子详情、楼中楼、图片缩放与保存、视频播放

**登录后**

- 消息：回复我的、@我的
- 关注或取消关注用户、贴吧，查看关注与粉丝列表
- 收藏帖子，收藏保存在贴吧账号里，与官方客户端同步
- 贴吧签到：一键为关注的贴吧签到，也可以设置每天第一次打开时自动签到
- 主贴、楼层、楼中楼点赞
- 发布文字主题，回复帖子、楼层与楼中楼；回复支持图片、贴吧表情和本机草稿
- 编辑本人资料（昵称、简介和性别）

注意！发布、回复和个人资料编辑目前仅为实验性功能，均通过非官方接口完成，可能存在风险，会触发百度官方风控导致删帖等。发帖和回复需要在设置里显式打开。

**本机功能**

- 关键词 / 用户 / 贴吧屏蔽
- 浏览历史与阅读位置
- 外观可跟随系统或手动选择浅色 / 深色
- 深链接 `tiebapure://thread/...`、`tiebapure://forum/...`、`tiebapure://search/...`，以及 `https://tieba.baidu.com/p/...`

## 构建

已验证的开发环境为 JDK 17、Android SDK 36 和 Build Tools 36.0.0。

```bash
./gradlew test lintRelease assembleDebug assembleRelease bundleRelease
```

Debug 和 Release APK 输出在 `app/build/outputs/apk/`，Release AAB 输出在 `app/build/outputs/bundle/release/`。Release 会开启 R8 压缩、优化、混淆和资源收缩。未配置签名时，Gradle 会生成 **未签名** 的 Release APK / AAB，CI 不需要签名密钥。

## 发布签名

签名只从以下环境变量读取，四个必须同时设置或同时缺省；只配一部分会在配置阶段失败：

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

签过名的 APK 是 `app/build/outputs/apk/release/app-release.apk`。四个变量都缺省时是 `app-release-unsigned.apk`。

## 模块

- `app`：应用容器、导航和依赖组装
- `core:model`：与平台无关的领域模型和策略
- `core:protocol`：由 `proto/` 生成的 lite Protobuf
- `core:network`：HTTPS 传输、协议映射和贴吧接口
- `core:data`：Room、DataStore、加密账号存储和仓库
- `core:designsystem`：主题和可复用阅读 UI
- `core:media`：图片浏览、受保护的远程媒体下载和 Media3 视频
- `feature:*`：首页、进吧、搜索、帖子、账号、设置和发帖编辑器

`proto/` 是两端共用的最小贴吧协议定义，只包含应用实际发送或读取的字段。修改字段编号或 wire 类型时，必须同时增加手写 wire fixture，并验证请求编码、响应解码和领域模型映射。iOS 客户端在 [TiebaPure-iOS](https://github.com/infinityf4p/TiebaPure-iOS) 中自行生成 Swift 产物。

## 开源许可

TiebaPure-Android 以 [GPL-3.0-only](LICENSE) 发布，不提供任何担保。APK 和 AAB 内含项目 GPL、Apache License 2.0、Protocol Buffers BSD 3-Clause 以及 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 声明

本项目与百度公司、百度贴吧官方无隶属、授权或认可关系。

“百度”“贴吧”及相关名称与标识归其各自权利人所有。

## 感谢

感谢 [TiebaLite](https://github.com/HuanCheng65/TiebaLite) 为项目早期开发提供参考，也感谢 [aiotieba](https://github.com/lumina37/aiotieba) 对贴吧协议的整理与开源实现。

感谢 [LINUX DO](https://linux.do/) 社区的支持。
