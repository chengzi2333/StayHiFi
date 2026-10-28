package com.chengzi.stayhifi.util

/**
 * Task C3：精确人工 override（spec §10.1 第 1 层）。
 *
 * 只收有依据的 Build.MODEL，纠正已知目录误差；精确匹配，不做前缀/包含/区域猜测。
 * 依据均标注来源，不声称超出证据的结论。
 */
internal val DEVICE_MODEL_OVERRIDES: Map<String, String> = mapOf(
    // PHZ110：目录展示口径收窄为 Find X7（spec §2.2/§10.2 验收样例）。
    "PHZ110" to "Find X7",
    // NX741J：用户提供的纠正（spec §2.2/§10.2）。现有 Google 目录只到
    // "nubia Z80"，无公开数据确认 Ultra 后缀——这是人工纠正，不代表 Google 口径。
    "NX741J" to "nubia Z80 Ultra",
)
