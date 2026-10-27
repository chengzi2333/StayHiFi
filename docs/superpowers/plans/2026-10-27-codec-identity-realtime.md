# 编码器身份、只读 Snapshot 与实况解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 9 字段写入协议与 AIDL 的前提下，统一编码器身份和只读快照，修复 LHDC 多段实况解析、播放中栈回报 idle 的显示，并收敛标准编码器诊断噪音。

**Architecture:** 特权服务将核心配置、能力、系统身份目录和分阶段读取状态编码为增量 JSON；App 侧只通过共享 snapshot parser、identity resolver 和 formatter 消费。dumpsys 先拆段再裁决，实时状态用显式枚举表达；旧解析入口保留为兼容包装，旧档案继续走 `CodecConfig.summary()`。

**Tech Stack:** Android（minSdk 29）、Kotlin、kotlinx.serialization JSON、JUnit4、Gradle/AGP、Shizuku/Stellar、Jetpack Compose。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md`（本计划仅覆盖 §1-4、§6、§8/9/12/13 中相关约束及 §14-16 的编码器/实况部分）。

## Global Constraints

- 基线为 v1.2.1(6)、master `d3adf81`；协调方已于 2026-10-27 使用 JDK 27 实际重跑基线 `./gradlew test --rerun-tasks`，结果 119 tests / 0 failures / 0 errors。后续任务必须重跑并报告新结果。
- 所有 Gradle 命令显式使用 `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home`，并将该 JDK 的 `bin` 放在 `PATH` 最前；每个 RED/GREEN 结果都必须来自当次真实运行。
- 严格顺序执行 Task 1→8；不得并行修改 `CodecService.kt`、`MainActivity.kt`、`ApplyWorker.kt` 或 `BtStateParser.kt`。每个任务只暂存本任务文件，commit message 末尾附 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；不 push、不发布、不改版本号。
- `CodecConfig` 仍是原 9 字段；旧档案 JSON、`toJson()/fromJson()`、`writeConfig`、恢复默认和读回匹配语义不变。`ICodecService.aidl` 的方法签名和 `writeConfig` 行为不变，`readConfig` 只追加字段。
- 不实现或预留能力目标派生、`TargetAssessment`、TriggerGate、Root、机型名、aptX 专属分支、手动档位/UI/档案字段。不得修改 `ApplyPolicy`、`Negotiation` 或 `BEST_CS1`；本计划只为既有派生增加回归断言。
- 身份与能力解耦：不得仅凭 cs1/cs3 猜身份，不得把 native type 14/18/19 固化为跨机型协议；`localCapabilities` 仅作本机能力诊断，身份启发式和目标能力只能使用当前连接的 `selectable`。
- supported types 仅在特权服务侧缓存/限频，且只有 nativeType 与 current 匹配时才可作当前身份。系统身份→extended→已实证 legacy→caps heuristic→`vendor(N)`；系统/旧映射冲突保留诊断，旧启发式不得反向覆盖系统身份。
- 首页既有 3 秒轮询的 dumpsys 次数不增加，ListenerService 30 秒路径不新增 dumpsys；多段解析复用同一轮 dump。除 readConfig 内受缓存约束的只读身份调用外，不新增常驻 binder 轮询。
- 日志仍只用每进程 200 行内存 LogStore；不持久化、不上传、不新增 MAC/设备名收集。单阶段异常文本最多 160 字符，ambiguous 段头最多 4 个，dump clues 仍最多 24 行；同类异常必须去重。
- OPD/NX/PHZ/iQOO fixture 只固定 spec 已给出的字段，未提供的 cs/通道等值不得冒充原始事实。外部 ROM 未验证前，不得宣称 MediaTek 或全部 OPD 身份源已修复。

## Review Focus

1. OEM 返回重复 nativeType、冲突/空白/`UNKNOWN CODEC`（含 `UNKNOWN CODEC(18)`）/超长名称时：不得取第一条硬认，须留 issue 并进入下一身份来源（Task 4 Step 1-4）。
2. local 暴露 192 而 selectable 仅 96 时：不得借 local 提升身份或能力；presentation 只能把 selectable 交给 resolver（Task 5 Step 1-4）。
3. 并发首页/Apply 刷新与蓝牙重启撞上目录刷新时：至多一个 loader 执行，不能在锁内做 binder IO，invalidate 后不得继续返回旧目录（Task 3 Step 1-4）。
4. 同版段头重复且值冲突、raw bitrate 超过 Int 可显示范围时：前者必须 ambiguous，后者必须无可用码率；均不得拼段、溢出成负值或猜一个结果（Task 6 Step 1-4）。
5. 栈在 `isMusicActive=true` 时回报 -1，或构造出 `AVAILABLE` 但缺 bitrate 的不一致状态时：分别显示“暂无法确认”，不得显示“未在播放”或“null kbps”（Task 7 Step 1-4）。

## File Structure

- **Snapshot 契约：**新建 `codec/CodecStateSnapshot.kt`（只读模型与读取状态）、`codec/CodecStateSnapshotJson.kt`（兼容解析/编码）及对应测试；测试共用 `codec/CodecDeviceFixtures.kt`。
- **特权服务读取：**新建 `shell/CodecReflectionReader.kt`（局部反射与状态保留）、`shell/SupportedTypesCatalogCache.kt`（目录缓存/单飞/失效）；修改 `shell/CodecService.kt` 的 readConfig、代理回调与反射接线。
- **身份与展示：**新建 `codec/CodecIdentity.kt`、`CodecIdentityResolver.kt`、`CodecSummaryFormatter.kt`、`CodecSnapshotPresenter.kt`、`CodecIdentityDiagnosticGate.kt`；仅在 `CodecConfig.kt` 增加 family 版 quality overload，保留旧 API。
- **实况：**重写 `codec/BtStateParser.kt` 的段落选择内部实现并保留旧包装；新建 `codec/RealtimeDisplay.kt`、`RealtimeDisplayFormatter.kt`。修改 `VendorDiagnostics.kt` 以消费 identity/result。
- **调用方：**修改 `MainActivity.kt`、`ApplyWorker.kt`、`ListenerService.kt` 的 readConfig 解析和用户可见摘要；修改 `HomeScreen.kt` 的实时枚举与文案。AIDL、ProfileRepository、ApplyPolicy、Negotiation 的实现文件不动。
- **测试：**新增 snapshot/reflection/cache/identity/formatter/presentation/realtime 测试；扩充 `BtStateParserTest.kt`、`VendorDiagnosticsTest.kt`、`MasksTest.kt` 和 `ApplyPolicyTest.kt`，既有非语义变化用例不得改预期。

---

### Task 1: 共享只读 Snapshot 与增量 JSON

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecStateSnapshot.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecStateSnapshotJson.kt`
- Create: `app/src/test/java/com/chengzi/stayhifi/codec/CodecStateSnapshotJsonTest.kt`
- Create: `app/src/test/java/com/chengzi/stayhifi/codec/CodecDeviceFixtures.kt`

**Interfaces:**
- Consumes: 现有 `@Serializable CodecConfig` 与旧 `{current, selectable}` JSON。
- Produces: 后续任务统一使用以下公共契约；`current` 无默认值，使 `{error:...}`/缺 current 不能伪装成有效 snapshot，显式 `current:null` 则可保留事实。

```kotlin
@Serializable enum class ApiReadState { OK, NULL, EMPTY, ERROR, UNAVAILABLE }
@Serializable data class SupportedCodecType(val nativeType: Int, val codecId: Long, val codecName: String)
@Serializable data class ExtendedCodecIdentity(val codecId: Long? = null, val codecName: String? = null)

@Serializable data class CodecReadStatus(
    val codecStatusNull: Boolean = false, val currentNull: Boolean = false,
    val localCount: Int = 0, val selectableCount: Int = 0,
    val currentSelectable: Boolean? = null,
    val bluetoothState: String = "UNKNOWN", val a2dpProxyState: String = "UNKNOWN",
    val codecStatusState: ApiReadState = ApiReadState.OK,
    val currentState: ApiReadState = ApiReadState.OK,
    val localCapabilitiesState: ApiReadState = ApiReadState.UNAVAILABLE,
    val selectableState: ApiReadState = ApiReadState.OK,
    val errors: Map<String, String> = emptyMap(),
)

@Serializable data class IdentityDiagnostics(
    val supportedTypesStatus: ApiReadState = ApiReadState.UNAVAILABLE,
    val extendedTypeStatus: ApiReadState = ApiReadState.UNAVAILABLE,
    val serviceUid: Int? = null, val errors: Map<String, String> = emptyMap(),
)

@Serializable data class CodecStateSnapshot(
    val current: CodecConfig?,
    val selectable: List<CodecConfig> = emptyList(),
    val localCapabilities: List<CodecConfig> = emptyList(),
    val supportedTypes: List<SupportedCodecType> = emptyList(),
    val extendedIdentity: ExtendedCodecIdentity? = null,
    val readStatus: CodecReadStatus = CodecReadStatus(),
    val identityDiagnostics: IdentityDiagnostics = IdentityDiagnostics(),
)

object CodecStateSnapshotJson {
    fun parse(json: String): CodecStateSnapshot
    fun encode(snapshot: CodecStateSnapshot): String
}
```

- [ ] **Step 1: 写失败测试与共享 fixture。**

在 `CodecStateSnapshotJsonTest` 固定四组断言：旧两字段 JSON 保留 current/selectable 且新身份源为 UNAVAILABLE；全字段 encode→parse 保留 local、supportedTypes、extendedIdentity、serviceUid 和 `currentSelectable`；`NULL`、`EMPTY`、`ERROR` 以及 `current:null` 各自 round-trip，ERROR 的 stage key 不丢；未知附加字段可忽略，而 `{error:"readConfig:..."}`/缺 current/损坏 JSON 抛异常。断言 parse 后 `localCount/selectableCount` 与数组实际大小一致。

`CodecDeviceFixtures.kt` 只放后续共享数据：NX `type=14,currentRate=8,caps=11`；OPD `type=18,currentRate=8,bits=2,caps=10,cs1=0x8006`；PHZ `type=19,currentRate=32,bits=2,caps=42`；iQOO Pad `type=8,currentRate=2,bits=1,caps=2`；iQOO 15 沿用现有 type10 golden。OPD dump 固定 V3=`MID/500000`、V5=`{}`，PLK dump 固定 V3 `raw=-1`。未证实字段用显式 helper 默认值，不命名为“完整原始日志”。

- [ ] **Step 2: 运行测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.CodecStateSnapshotJsonTest --rerun-tasks`
Expected: 因新模型/parser 尚不存在而编译 FAIL；记录真实输出，不写成已预先通过。

- [ ] **Step 3: 最小实现模型和 JSON parser。**

使用 `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }`。属性顺序保持 `current, selectable` 在前，新字段随后；parse 后仅用实际数组长度归一化两个 count，不把 empty 改成 OK，也不从 current 推断 extended/supported 状态。不要修改 `CodecConfig`、AIDL 或任何调用方。

- [ ] **Step 4: 运行新测试与全量回归确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: 新测试 PASS，既有 119 个测试无预期变更；记录新的实际总数。

- [ ] **Step 5: 提交 Task 1。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/CodecStateSnapshot.kt app/src/main/java/com/chengzi/stayhifi/codec/CodecStateSnapshotJson.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecStateSnapshotJsonTest.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecDeviceFixtures.kt
git commit -m "Task 1: 增加只读编码器快照契约" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: readConfig 分阶段反射读取与 OEM 失败隔离

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/CodecReflectionReader.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt:76-99,368-434`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/CodecReflectionReaderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ApiReadState`、`CodecStateSnapshot`、`CodecStateSnapshotJson.encode()`。
- Produces: 服务内部统一反射结果；Android 类型只以 `Any` 进入，便于纯 JUnit fake，不在公共 codec 模型中引入 framework 依赖。

```kotlin
internal data class ReflectedValue<T>(
    val state: ApiReadState, val value: T? = null, val error: String? = null,
)

internal object CodecReflectionReader {
    fun codecStatus(a2dp: Any, device: Any): ReflectedValue<Any>
    fun readSnapshot(
        status: ReflectedValue<Any>, a2dp: Any,
        supportedTypes: ReflectedValue<List<SupportedCodecType>>,
        serviceUid: Int, bluetoothState: String, a2dpProxyState: String,
    ): CodecStateSnapshot
    fun supportedTypes(a2dp: Any): ReflectedValue<List<SupportedCodecType>>
}
```

- [ ] **Step 1: 用 fake 反射对象写失败测试。**

覆盖 `getCodecStatus()==null`、`getCodecConfig()==null`、二者分别抛异常；local/selectable 分别为 null、空、异常和正常列表；`getSupportedCodecTypes` 为 API 不存在（UNAVAILABLE）、null、EMPTY、SecurityException/InvocationTargetException（ERROR）及正常目录；extended getter 失败时 current/selectable 仍完整。断言 `currentSelectable` 仅在 current 与 selectable 均可读时为 true/false，否则 null；local 与 selectable 不混用。所有异常断言保留阶段 key、root cause 类型且不超过 160 字符。

- [ ] **Step 2: 运行 reflection 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.CodecReflectionReaderTest --rerun-tasks`
Expected: 新 reader 不存在导致编译 FAIL。

- [ ] **Step 3: 实现局部读取并接入 readConfig。**

将现有 9 字段反射转换移入 reader；逐项读取 local(`getCodecsLocalCapabilities`)、selectable、extended(`getExtendedCodecType`) 和 supported(`getSupportedCodecTypes` 条目的 **`getNativeCodecType`**/`getCodecId`/`getCodecName`)。NoSuchMethod/ClassNotFound 记 UNAVAILABLE，null/empty/error 不合并；解包 InvocationTargetException，错误文本截到 160。

`CodecService.readConfig` 保持 getA2dp/findDevice 的既有 error JSON；取得设备后，即使 codecStatus/current 为 null 或反射异常，也返回含 `current:null`、状态和 errors 的 snapshot，不再用 `!!`。仅 metadata 失败绝不清空已成功 current/selectable。填入 `Process.myUid()`、安全读取的蓝牙/A2DP 状态；supported 暂直接调用 reader，下一任务加缓存。writeConfig、currentConfig 写读回路径和 AIDL 不动。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: 新 reflection 测试与所有旧测试 PASS；旧调用方遇 current:null 可失败返回而不崩溃。

- [ ] **Step 5: 提交 Task 2。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/CodecReflectionReader.kt app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt app/src/test/java/com/chengzi/stayhifi/shell/CodecReflectionReaderTest.kt
git commit -m "Task 2: 隔离编码器只读元数据异常" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: supported types 服务侧缓存、限频与失效

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/SupportedTypesCatalogCache.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt:76-99,152-154,253-296`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/SupportedTypesCatalogCacheTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `ReflectedValue<List<SupportedCodecType>>` 与 `CodecReflectionReader.supportedTypes()`。
- Produces: `internal class SupportedTypesCatalogCache(private val nowMs: () -> Long)`，方法 `get(loader: () -> ReflectedValue<List<SupportedCodecType>>): ReflectedValue<List<SupportedCodecType>>` 和 `invalidate(): Unit`。

- [ ] **Step 1: 写缓存失败测试。**

用可控 clock/loader 断言：OK 与 UNAVAILABLE 在 60,000ms 内只加载一次；NULL/EMPTY/ERROR 在 10,000ms 内只加载一次，超过各自 TTL 才重试。两个并发线程且首个 loader 阻塞时 loaderCount 必须为 1；有 stale 时另一调用返回 stale，无值时返回 UNAVAILABLE/`refresh-in-flight`，不得等待或再发 binder。`invalidate()` 后立即加载新目录；loader 抛出的异常只能变成 ERROR 结果，不能破坏缓存状态。

- [ ] **Step 2: 运行 cache 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.SupportedTypesCatalogCacheTest --rerun-tasks`
Expected: cache 类型不存在导致编译 FAIL。

- [ ] **Step 3: 实现无 IO 锁的缓存并接入服务。**

缓存锁只保护状态读写，loader 在锁外执行；用 in-flight 标志限制单飞，禁止在 ApplyLock、mThread Handler 或缓存 synchronized 块内调用 binder。`readConfig` 改为 `cache.get { reader.supportedTypes(a2dp) }`。A2DP `onServiceConnected/onServiceDisconnected`、adapter 非 ON 及 `destroy()` 均 invalidate；蓝牙 OFF→ON 后首次读取必须重新加载。TTL 使用 `SystemClock.elapsedRealtime()`，不使用墙钟。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: cache 全部测试 PASS，无新增测试/旧测试失败。

- [ ] **Step 5: 提交 Task 3。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/SupportedTypesCatalogCache.kt app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt app/src/test/java/com/chengzi/stayhifi/shell/SupportedTypesCatalogCacheTest.kt
git commit -m "Task 3: 缓存系统编码器身份目录" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: CodecIdentityResolver 与 CodecSummaryFormatter

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentity.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentityResolver.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecSummaryFormatter.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/CodecConfig.kt:99-130`（只新增 overload，旧 `summary()` 不改）
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/CodecIdentityResolverTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/CodecSummaryFormatterTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/MasksTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1 snapshot/model、`Masks.codecName()`、`capsRateMask()` 和既有 LHDC/LDAC cs1 表。
- Produces: 后续 UI、ApplyWorker、ListenerService、BtStateParser 与后续计划共用以下精确类型。

```kotlin
enum class CodecFamily { SBC, AAC, APTX, APTX_HD, APTX_ADAPTIVE, LHDC, LDAC, LC3, OPUS, UNKNOWN }
enum class CodecIdentitySource { SYSTEM_TYPE, EXTENDED_CONFIG, VERIFIED_LEGACY, CAPABILITY_HEURISTIC, UNKNOWN }
enum class CodecIdentityIssue { SUPPORTED_TYPE_CONFLICT, SUPPORTED_TYPE_INVALID, SYSTEM_LEGACY_CONFLICT, EXTENDED_INVALID }

data class CodecIdentity(
    val nativeType: Int, val family: CodecFamily, val displayName: String,
    val codecId: Long? = null, val source: CodecIdentitySource,
)
data class CodecIdentityResolution(val identity: CodecIdentity, val issues: Set<CodecIdentityIssue>)

object CodecIdentityResolver {
    fun resolve(
        current: CodecConfig, selectable: List<CodecConfig>,
        supportedTypes: List<SupportedCodecType> = emptyList(),
        extendedIdentity: ExtendedCodecIdentity? = null,
    ): CodecIdentityResolution
    fun resolve(snapshot: CodecStateSnapshot): CodecIdentityResolution?
}

data class CodecSummary(val codec: String, val rate: String, val bits: String, val quality: String?) {
    fun text(): String
}
object CodecSummaryFormatter {
    fun format(config: CodecConfig, identity: CodecIdentity): CodecSummary
    fun formatText(config: CodecConfig, identity: CodecIdentity): String
}

// Masks 成员 overload；既有 codecType/caps 签名保留
fun qualityLabel(family: CodecFamily, cs1: Long): String?
```

- [ ] **Step 1: 写身份、质量档与设备 fixture 失败测试。**

Resolver 至少断言：OPD native18 的系统 `LHDC V3` 在 caps=10 时仍为 SYSTEM_TYPE；系统 V3 覆盖 legacy type10/V5 并产生一次 `SYSTEM_LEGACY_CONFLICT` issue；完全相同重复项可去重，名称或 ID 冲突项整组忽略并进 extended；blank、`UNKNOWN CODEC` 与 `UNKNOWN CODEC(18)`、超过 128 字符的名称均不可信；extended 仅作第二来源；标准 0..6、已实证 7/10 走 VERIFIED_LEGACY；PHZ type19+caps42 才走 generic LHDC/CAPABILITY_HEURISTIC。无系统身份时 NX caps11、OPD caps10、iQOO type8+caps2 分别保持 `vendor(14)`、`vendor(18)`、`vendor(8)`/UNKNOWN，cs1/cs3 不得补认。

Formatter 断言 OPD 输出 `LHDC V3·96kHz·24bit·500kbps`；generic LHDC+`0x8009` 输出自适应；LDAC family 保持 AOSP 档位；UNKNOWN family 即使 cs1 形似 LHDC 也不解码；`CodecConfig.summary()` 旧字符串与全部旧 MasksTest 不变。只在 `ApplyPolicyTest` 新增回归：NX caps11 与 OPD caps10 的 `deriveTarget().sampleRate==8`，PHZ caps42 为 32；不得为实现这些断言修改 ApplyPolicy。

- [ ] **Step 2: 运行身份/formatter 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.CodecIdentityResolverTest --tests com.chengzi.stayhifi.codec.CodecSummaryFormatterTest --rerun-tasks`
Expected: 新身份类型/函数不存在导致编译 FAIL。

- [ ] **Step 3: 实现可信门槛、裁决顺序和共享 formatter。**

名称 trim 后非空、**大写后不得以 `UNKNOWN CODEC` 开头**（覆盖 `UNKNOWN CODEC(18)`）、长度≤128 才可信；系统目录先按 current.nativeType 过滤，名称或 codecId 冲突则忽略该 nativeType 的系统组。family 只做通用显示/诊断映射，不加入 aptX 策略。系统与 legacy 仅在 family 不同或明确 LHDC 版本互斥时发 conflict；generic LHDC 与已版本化名称不算冲突。snapshot overload 在 current==null 时返回 null，并只传 selectable。

新增 family 版 `Masks.qualityLabel` 复用现有 LDAC/LHDC magic 与低字节表，旧 codecType/caps overload 委托旧逻辑以保二进制源码行为；BEST_CS1 不动。formatter 不读取 local、不重新猜身份，`text()` 用既有 `·` 格式且 quality==null 时仅三段。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: 新旧身份、质量档和 ApplyPolicy 回归全部 PASS，无 192 目标变化。

- [ ] **Step 5: 提交 Task 4。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentity.kt app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentityResolver.kt app/src/main/java/com/chengzi/stayhifi/codec/CodecSummaryFormatter.kt app/src/main/java/com/chengzi/stayhifi/codec/CodecConfig.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecIdentityResolverTest.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecSummaryFormatterTest.kt app/src/test/java/com/chengzi/stayhifi/codec/MasksTest.kt app/src/test/java/com/chengzi/stayhifi/trigger/ApplyPolicyTest.kt
git commit -m "Task 4: 统一编码器身份裁决与摘要" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 5: 首页、ApplyWorker 与 ListenerService 共用 Presentation

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecSnapshotPresenter.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentityDiagnosticGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:753-781,836-895,961-1168,1248-1256,1275-1284`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:68-197,282-325,336-363,384-410`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt:329-389`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/CodecSnapshotPresenterTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/CodecIdentityDiagnosticGateTest.kt`

**Interfaces:**
- Consumes: Task 1 parser、Task 4 resolver/formatter/issues。
- Produces: `data class CodecPresentation(val current: CodecConfig, val identity: CodecIdentity, val summary: CodecSummary, val issues: Set<CodecIdentityIssue>)`；`object CodecSnapshotPresenter { fun present(snapshot: CodecStateSnapshot): CodecPresentation? }`。current:null 返回 null，绝不构造 nativeType=-1 的假身份。

- [ ] **Step 1: 写 presentation 与冲突去重失败测试。**

同一 OPD snapshot 经 presenter 必须得到 Task 4 的 identity 和 formatter 文本；current:null 返回 null。构造 local=192、selectable=96 的 snapshot，断言仍不得得到 CAPABILITY_HEURISTIC/192 身份能力上下文。冲突 gate 对同 mac+current+系统 identity 跨多个调用方只放行一次，系统 identity 改变可再放行，reset 后可重记。

- [ ] **Step 2: 运行 presentation 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.CodecSnapshotPresenterTest --tests com.chengzi.stayhifi.codec.CodecIdentityDiagnosticGateTest --rerun-tasks`
Expected: presenter/gate 不存在导致编译 FAIL。

- [ ] **Step 3: 最小实现并替换三条消费链。**

MainActivity 的首页、连接刷新和录制向导，ApplyWorker 的 `readState`，ListenerService 的通知读取均改用 `CodecStateSnapshotJson.parse` + presenter，不再各自解析 current/selectable 或直接 `current.summary()`。当前 null 时首页清空参数并禁用手动 apply，ApplyWorker 安静停止且不写 origin/不写配置/不发成功通知，通知显示等待；保留既有 error JSON 和超时处理。

首页用于 dumpsys 匹配的身份固定取 presentation.identity，**不得让 dump 反向改写身份来源**；Task 6 在结果唯一且无歧义时，可用同一 `result.state.codec` 补充精确版本显示（如 generic LHDC → LHDC V3），AMBIGUOUS/NO_SECTION 不得覆盖。fullBars 仍用现有 current+selectable/ApplyPolicy。ApplyWorker 将 settled identity 传给同 codecType 的派生候选、通知与读回日志；跨 codecType 或历史档案/恢复默认没有身份上下文时保留旧 `summary()`。ListenerService 当前参数和漂移读回用同一 formatter。

三处发现 `SYSTEM_LEGACY_CONFLICT` 时共用进程级 gate 写一条 `IDENTITY_CONFLICT`（含 nativeType、系统名、legacy 名，不含新隐私字段），清空日志时 reset。此任务仍使用旧 BtStateParser API，下一任务再切换 result。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: presentation/gate 测试 PASS，所有 Android 调用方编译且既有策略测试不变。

- [ ] **Step 5: 提交 Task 5。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/CodecSnapshotPresenter.kt app/src/main/java/com/chengzi/stayhifi/codec/CodecIdentityDiagnosticGate.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecSnapshotPresenterTest.kt app/src/test/java/com/chengzi/stayhifi/codec/CodecIdentityDiagnosticGateTest.kt
git commit -m "Task 5: 统一各入口编码器展示上下文" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 6: BtStateParser 逐段裁决与 OPD 回归

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt:18-139`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:1076-1148`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:93-139`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/BtStateParserTest.kt`

**Interfaces:**
- Consumes: Task 4 `CodecIdentity`、Task 5 presentation.identity、共享 OPD/vivo/LDAC/AAC fixture。
- Produces: 后续实时与诊断共用 `BtStateParseResult`，旧 `parse(...): BtState?` 两个入口及 `BtState` 字段保留为包装。

```kotlin
enum class BtStateParseStatus { NO_SECTION, AMBIGUOUS_SECTION, NOT_STREAMING, NO_BITRATE, AVAILABLE }
data class BtStateParseResult(
    val status: BtStateParseStatus, val state: BtStateParser.BtState? = null,
    val candidateSections: List<String> = emptyList(),
)

// BtStateParser 成员；旧 parse(...) 包装保留
fun parseResult(dump: String, identity: CodecIdentity): BtStateParseResult
```

- [ ] **Step 1: 写逐段失败测试，保留既有非语义变化用例。**

新增断言：精确 LHDC V3 只返回 OPD V3 的 MID/raw=500000/kbps=500；generic LHDC 面对 V3 有效+V5 `{}` 仍只选 V3，state.codec 不得变成 V5；两个 LHDC 段都有正码率返回 AMBIGUOUS_SECTION、state=null、段头最多 4 个；多个无有效码率段也 ambiguous；精确 V3 不存在时不得借 V5 字段，但旧无版本 `A2DP LHDC State` 可作唯一 legacy 候选。重复同名精确段若值冲突同样 ambiguous。`Long.MAX_VALUE` 不得溢出成负 kbps，而归为 NO_BITRATE。既有 vivo V5、LDAC -1、AAC 缺字段/非数字、ABR、旧段名兼容测试继续走旧 parse 包装并原样通过。

- [ ] **Step 2: 运行 parser 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.BtStateParserTest --rerun-tasks`
Expected: 新 result API 不存在或旧跨段实现不满足断言而 FAIL。

- [ ] **Step 3: 实现先拆段、后选择、再单段提取。**

先解析全部 `A2DP ... State:` 为独立 section；每段单独保存 header/quality/raw/notStreaming/normalized bitrate。优先 normalized 精确段名；精确缺失时只允许同族泛称或旧无版本段。generic 多候选仅在“恰一个 AVAILABLE 正码率段”时可选，其余返回 ambiguous，任何路径都不得跨段保留变量。raw>0 但归一化超出 Int 范围视为 NO_BITRATE。旧 parse 包装返回 result.state，因此 ambiguous 自然为 null。

MainActivity/ApplyWorker 改用 presentation.identity + parseResult，暂以 `result.state` 保持旧实时接口；诊断分类下一任务完整接入。MainActivity 仅在结果唯一且无歧义时用同段 `state.codec` 补充精确版本显示，AMBIGUOUS/NO_SECTION 保持 presentation 名称，禁止跨段覆盖。不得增加 dump 次数或修改 shell 过滤条件。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: OPD、多段和全部旧 parser 测试 PASS；无 V3/V5 字段混合。

- [ ] **Step 5: 提交 Task 6。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/test/java/com/chengzi/stayhifi/codec/BtStateParserTest.kt
git commit -m "Task 6: 按独立段落解析蓝牙实况" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 7: RealtimeDisplay 四态与 PLK110

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/RealtimeDisplay.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/RealtimeDisplayFormatter.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt:35-40,101-125`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:1013-1018,1024-1031,1043-1052,1078-1162`
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt:39-75,278-325,390-432`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/BtStateParserTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/RealtimeDisplayFormatterTest.kt`

**Interfaces:**
- Consumes: Task 6 `BtStateParseResult`、PLK110 fixture、isMusicActive。
- Produces: `enum class RealtimeDisplay { NOT_PLAYING, AVAILABLE, NO_DATA, STACK_REPORTS_IDLE }`；`data class RealtimeState(val display: RealtimeDisplay, val bitrateKbps: Int? = null, val qualityMode: String? = null)`；`BtStateParser.realtimeDisplay(isMusicActive: Boolean, result: BtStateParseResult): RealtimeState`；保留 state 参数 overload 供旧调用/测试。`RealtimeDisplayFormatter.text(state: RealtimeState): String` 为首页唯一文案入口。

- [ ] **Step 1: 写四态和文案失败测试。**

PLK 精确 V3 解析必须为 NOT_STREAMING；`isMusicActive=true`→STACK_REPORTS_IDLE 且 `text()=="暂无法确认"`，`false`→NOT_PLAYING/`未在播放`。正码率→AVAILABLE 并保留 ABR/码率，NO_SECTION、AMBIGUOUS_SECTION、NO_BITRATE→NO_DATA。既有 LDAC -1 用例只把“active+-1=未播放”的旧预期改为 STACK_REPORTS_IDLE，其余三态断言不改。构造 AVAILABLE+null bitrate 时 formatter 也必须返回“暂无法确认”。

- [ ] **Step 2: 运行实时测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.BtStateParserTest --tests com.chengzi.stayhifi.codec.RealtimeDisplayFormatterTest --rerun-tasks`
Expected: 新枚举/formatter 不存在，且旧 active+-1 预期 FAIL。

- [ ] **Step 3: 实现枚举决策并接入首页。**

判定顺序固定为：music=false→NOT_PLAYING；music=true+AVAILABLE 且有合法 bitrate→AVAILABLE；music=true+NOT_STREAMING→STACK_REPORTS_IDLE；其余→NO_DATA。仅 AVAILABLE 携带 bitrate/quality。可保留计算属性 `RealtimeState.notStreaming` 作为旧源码兼容，但它只在 NOT_PLAYING 为 true，新 UI 不得再靠布尔组合裁决。

`HomeUiState` 用 `realtimeDisplay: RealtimeDisplay = NO_DATA` 替换 `realtimeNotStreaming`；RealtimeSection 使用 formatter，NO_DATA 保持“—（本机暂不支持抓取）”，STACK_REPORTS_IDLE 不显示“系统不开放”反馈指引，AVAILABLE 保持 `400 kbps · ABR（自适应）` 格式。清空/超时状态显式复位枚举。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: PLK/LDAC 四态和 formatter 测试 PASS，首页编译，无旧布尔字段引用。

- [ ] **Step 5: 提交 Task 7。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/RealtimeDisplay.kt app/src/main/java/com/chengzi/stayhifi/codec/RealtimeDisplayFormatter.kt app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt app/src/test/java/com/chengzi/stayhifi/codec/BtStateParserTest.kt app/src/test/java/com/chengzi/stayhifi/codec/RealtimeDisplayFormatterTest.kt
git commit -m "Task 7: 区分播放中与栈回报空闲" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 8: 标准编码器实时诊断收敛，保留 vendor 证据

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnostics.kt:13-32`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:941-956,1076-1148`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:93-139`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticsTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticGateTest.kt`

**Interfaces:**
- Consumes: Task 4 identity、Task 6 result、Task 7 display；现有 `VendorDiagnosticGate` 和 24 行 clues。
- Produces: `VendorDiagnostics.shouldLog(identity: CodecIdentity): Boolean`、`VendorDiagnostics.sampleKind(result: BtStateParseResult): String`、`VendorDiagnostics.realtimeIssue(identity: CodecIdentity, result: BtStateParseResult): String?`。旧 `shouldLog(codecType,caps)` 与 `sampleKind(state)` 保留兼容。

- [ ] **Step 1: 写诊断门控失败测试。**

SBC、AAC、LDAC 等已知标准 identity 对 NO_SECTION/NO_BITRATE 的 `realtimeIssue` 均为 null，`shouldLog=false`；system/extended/heuristic 识别且 nativeType 在厂商区间的 LHDC 仍 `shouldLog=true`，UNKNOWN/`vendor(N)` 也保留 no-section/no-bitrate 原因。NOT_STREAMING 无论 music 上下文都不得生成“无码率字段/无匹配段落”issue；AMBIGUOUS_SECTION 映射 `ambiguous-section`，原因只含最多 4 个候选段头。既有 vendor gate 同 raw 同类只记一次、状态变化可重记的测试不改。

- [ ] **Step 2: 运行 diagnostics 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticsTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticGateTest --rerun-tasks`
Expected: identity/result overload 尚不存在导致编译 FAIL。

- [ ] **Step 3: 实现诊断分类并接入两处 dump 消费。**

`shouldLog(identity)` 仅对 UNKNOWN 或厂商 native 区间内的 LHDC 放行，标准 family 和既有 type10 保持安静；旧函数原样委托既有规则。`sampleKind(result)` 映射五类小写证据名，`realtimeIssue` 先过 identity 门控，再仅对 no-section/no-bitrate/ambiguous 返回原因。

MainActivity 的 timeout/error JSON 等抓取诊断也先检查 `shouldLog(identity)`，标准编码器不得再刷“实际传输抓取诊断”；复用本轮 `BtStateParseResult`，不得重新 parse/dump。vendor 的 NOT_STREAMING 只经现有 gate 留一次含 `isMusicActive=true/false/unknown` 的 `not-streaming` 证据，不走 realtimeIssue；available 恢复时仍 `logBtDiag(null)`。ApplyWorker 同样保留 vendor raw+evidence，标准 identity 不进入其 dump 诊断块，因此不得为标准编码器额外发起 dump。

- [ ] **Step 4: 运行全量测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`
Expected: 新旧 diagnostics/gate 测试 PASS，SBC/AAC 无实时诊断 issue，vendor 证据语义不变。

- [ ] **Step 5: 提交 Task 8。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnostics.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticsTest.kt app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticGateTest.kt
git commit -m "Task 8: 收敛标准编码器实时诊断" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

## Final Verification

- [ ] 运行完整单测：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`。Expected: 全部 PASS，报告新的实际 tests/failures/errors，不沿用 119 作为最终数量。
- [ ] 运行静态检查：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew lint`。Expected: BUILD SUCCESSFUL、0 errors。
- [ ] 构建调试包：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew assembleDebug`。Expected: BUILD SUCCESSFUL；本计划不改签名、版本或 release 配置。
- [ ] 运行 `git diff --exit-code -- app/src/main/aidl/com/chengzi/stayhifi/ICodecService.aidl`。Expected: 无输出、退出码 0；结合 CodecConfigTest/DeviceProfileTest/ApplyPolicyTest 确认 9 字段、档案与写入语义未变。
- [ ] 审查最终 diff：没有 ApplyPolicy/Negotiation 实现变更，没有 TriggerGate/Root/机型名/aptX/手动档位，没有新增 dumpsys 调用、持久日志、网络用途或 exported 组件。minified release 与外部真机矩阵由整分支后续计划统一验收，本计划不得伪报已完成。

## External Acceptance Notes（非本地通过声明）

- OPD2409：仅在真实 supportedTypes/extendedIdentity 有效时验收系统 LHDC V3、500kbps 设置档与同一 V3 实况段；若两者无效，记录“外部 ROM 身份源不可用、问题尚未闭环”，不得用 cs1 补认。caps=10 不派生 192。
- NX741J：收集完整 supported/extended/selectable；caps=11 仍只证明最高 96kHz，无系统证据则保持 vendor(14)。
- PHZ110 与 iQOO 15：分别回归既有 192kHz 读回和 type10 golden 显示/实况；iQOO Pad type8+caps2 不得被新身份源之外的 cs 值猜成 LHDC。
- PLK110：真实播放中 raw=-1 应显示“暂无法确认”；SBC/AAC 样本不再产生实时抓取噪音，vendor 的原始诊断仍可复制。
