package com.chengzi.stayhifi.codec

/** 当前连接的用户可见展示上下文；身份、摘要与诊断均来自同一次快照裁决。 */
data class CodecPresentation(
    val current: CodecConfig,
    val identity: CodecIdentity,
    val summary: CodecSummary,
    val issues: Set<CodecIdentityIssue>,
)

object CodecSnapshotPresenter {

    /** current 缺失时不伪造身份；否则只复用 Task 4 的 resolver 与 formatter。 */
    fun present(snapshot: CodecStateSnapshot): CodecPresentation? {
        val current = snapshot.current ?: return null
        val resolution = CodecIdentityResolver.resolve(snapshot) ?: return null
        return CodecPresentation(
            current = current,
            identity = resolution.identity,
            summary = CodecSummaryFormatter.format(current, resolution.identity),
            issues = resolution.issues,
        )
    }
}
