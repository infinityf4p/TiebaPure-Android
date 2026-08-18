# Tieba Protobuf Inputs

部分贴吧接口使用 Protobuf 传输数据。本目录保存 TiebaPure 自行维护的最小协议定义，只包含应用实际发送或读取的字段；响应中的其他字段由 Protobuf 作为未知字段跳过。

字段编号、wire 类型以及 `optional` / `repeated` 语义都是协议的一部分。修改它们时必须同步增加手写 wire fixture，并验证请求编码、响应解码和领域模型映射，不能仅依赖同一份 schema 的编码再解码测试。

Android 的 Gradle Protobuf 任务只读取本目录。iOS 客户端使用同一套定义，源文件维护在 [TiebaPure-iOS](https://github.com/infinityf4p/TiebaPure-iOS)；改协议时需要同时验证两端。
