package com.chengzi.stayhifi.shell

/**
 * Task D2：特权通道选择（设计 §11.3）。
 * DataStore 只持久化字符串 [stored]，不存 enum ordinal；
 * 缺失/未知/大小写不符一律回 [SHIZUKU]（回退由 Coordinator 记一条日志）。
 * Stellar 继续与 Shizuku 共用 rikka 通道，不另立第四种 provider。
 */
enum class ProviderKind(val stored: String) {
    SHIZUKU("shizuku"),
    ROOT("root"),
    ;

    companion object {
        /** 精确匹配 root 才是 ROOT；其余（null/未知/大小写不符）回 SHIZUKU。 */
        fun fromStored(raw: String?): ProviderKind =
            if (raw == ROOT.stored) ROOT else SHIZUKU
    }
}
