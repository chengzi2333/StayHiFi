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

/**
 * Task D6：lease/acquire 层访问失败的事实分类（设计 §11.3/§11.4）。
 * 不含猜测语义；NEEDS_FOREGROUND_REVALIDATION 是唯一允许的后台冷启动终态。
 */
enum class ProviderAccessReason {
    /** 当前进程没有已验证的 READY transport，需前台重验（§11.1/§11.4.5） */
    NEEDS_FOREGROUND_REVALIDATION,
    /** lease 代次已过期（选择已切换/作废旧 generation） */
    STALE_GENERATION,
    /** lease 已 close */
    LEASE_CLOSED,
}

class ProviderAccessException(
    val reason: ProviderAccessReason,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: reason.name, cause)
