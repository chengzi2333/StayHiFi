# Task 29：R8 发布硬化（最小 keep 集，报错再补）。
# 原则：库 consumer 规则（kotlinx-serialization/Compose/Shizuku 等）与 AGP manifest 规则不手写；
# 反射目标全是 framework 类（BluetoothA2dp/SystemProperties），R8 不涉及。

# Shizuku API（含 manifest 里的 ShizukuProvider 等组件类）
-keep class dev.rikka.shizuku.** { *; }

# 我方 AIDL stub：Shizuku UserService 在 server 进程跨进程反序列化这些类
-keep class com.chengzi.stayhifi.ICodecService** { *; }

# Shizuku UserService 入口：以 Class.name 字符串经 Shizuku 跨进程反射启动，
# 不在 manifest 里，R8 看不见引用链，必须 keep
-keep class com.chengzi.stayhifi.shell.CodecService { *; }
