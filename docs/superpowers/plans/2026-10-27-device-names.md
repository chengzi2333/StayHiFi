# 对外机型名分层兜底 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用精确人工 override、厂商 SystemProperties、开发期生成的离线 Model 目录和 `Build.MODEL` 组成可测试、非阻塞的对外机型名兜底链。

**Architecture:** Python 标准库在开发期把固定快照的 Google supported_devices.csv 规范化成确定性 gzip JSON；Android 运行时只在 Application 的 IO 协程中单飞加载一次，并由进程内缓存提供同步、无 IO 查询。纯 Kotlin resolver 固定四层优先级，Compose 通过 `StateFlow` 在预载完成后刷新机型行；资产或属性不可用时始终安全回退。

**Tech Stack:** Python 3 标准库、Android、Kotlin、kotlinx-serialization-json、GZIP、StateFlow、JUnit4、AndroidX Instrumentation。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` §10；隐私/性能约束见 §12，测试约束见 §13。

## Global Constraints

- 只实施计划 C（spec §10）；不得修改 codec、Trigger、aptX、Root、AIDL、写入策略、版本号或发布配置，不授权 push。
- 基线为 v1.2.1(6)、master `d3adf81`；2026-10-27 已真实重跑基线 119 tests / 0 failures / 0 errors。每个 Android 测试命令统一前置：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH`。
- 固定顺序：精确人工 override → 厂商 SystemProperties → 离线 Model 映射 → `Build.MODEL`；override 仅精确匹配，不做前缀、包含或区域型号猜测。
- 首批运行时显示必须满足：`PHZ110=Find X7`、`OPD2409=OPPO Pad 4 Pro`、`NX741J=nubia Z80 Ultra`；NX741J 以 override 纠正目录的 `nubia Z80`，DPD2438B 继续优先使用已工作的 vivo 属性。
- 新 OPlus 键只收有实际 getprop 输出的 `ro.vendor.oplus.market.name` 与 `ro.vendor.oplus.market.enname`（OnePlus Ace 3 用户样本：https://github.com/fastfetch-cli/fastfetch/issues/1768）；不得宣称 PHZ110 的属性路径已验证。
- Google 源固定为 `https://storage.googleapis.com/play_public/supported_devices.csv`；首版快照日期 `2026-10-27`，原始字节 SHA-256 `a83be8f0ac7d963011394ead55416ce701dfe8b89bec89844ab3a24a254c3a23`（已独立复核）。原始输入以固定 `mtime=0` 的 `tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz` 版本化保存并随 Task 1 入库，硬上限 `600_000` bytes；禁止只放忽略的 `build/` 后宣称可复现。只允许生成脚本在开发期联网，运行时零新增网络、下载或更新通道。
- 目录不承诺全型号覆盖：空值、占位值和规范化后仍冲突的 Model 必须跳过并留计数，不得任意取第一条；现有 Google 数据不能证明 NX741J 的 Ultra 后缀。
- 体积目标约为 JSON 1.44MB / gzip 350KB；硬上限分别为 `1_600_000` / `400_000` bytes，超限生成失败，不得静默提高预算。运行时只随包携带紧凑 `.json.gz`，metadata 不参与查询。
- 每个进程最多解析一次；首屏主线程不得全量解压/解析或等待预载。未就绪、缺失、损坏、超限均回退属性/`Build.MODEL`，不阻塞启动、不崩溃、不显示空白。
- 不新增持久日志、上传、设备标识采集或网络权限；沿用现有日志仅内存、用户主动复制的边界。除 androidTest 依赖外不新增运行时库，不引入 Root/Robolectric。
- 新测试必须先取得旧实现 RED 证据；不得把纯 JVM fake 当作 Android SystemProperties、AssetManager 或 OEM 真机验证。无 Android 宿主时 connected 测试明确记为 NOT RUN。
- 每次提交末尾必须包含 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；不得 push 或发布。

## Review Focus

1. 同一 Model 对应多个不同营销名：跳过冲突键并回退，而不是受 CSV 行序影响（Task 1 Step 1/4 的 `test_conflicting_models_are_skipped`）。
2. 厂商属性返回空白、抛异常或直接返回内部型号：视为无效并继续查目录，不能让占位属性遮住更准确的离线名称（Task 3 Step 1/4 的 `test_invalid_property_values_fall_through`）。
3. 品牌大小写、已有品牌前缀及子品牌：不得生成 `Oppo Pad 4 Pro`、`OPPO OPPO ...` 或 `ZTE nubia ...`（Task 1 Step 1/4 的 `test_brand_formatting_is_canonical_and_deduplicated`）。
4. gzip 截断、JSON 损坏、解压后超限或重复并发 preload：缓存进入安全失败/就绪终态，打开器至多执行一次，查询不抛异常（Task 2 Step 1/4）。
5. 首屏查询发生在资产预载之前：同步查询不得触发打开/解析，先显示属性或 Model，预载后仅 StateFlow 驱动 UI 更新（Task 2 Step 1/4、Task 4 Step 3/6）。

## File Structure

- Create `tools/device_names/generate_catalog.py`：下载或读取固定 CSV/CSV.GZ 快照，校验原始字节 hash，规范化品牌/名称，消除冲突，输出确定性 gzip JSON 与可复现 metadata；仅开发期使用。
- Create `tools/device_names/test_generate_catalog.py`：以内存/临时 UTF-16 CSV fixture 覆盖格式、冲突、过滤、快照校验、确定性和体积预算。
- Create `tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz`：经源 hash 验证、固定 `mtime=0` 的版本化原始输入（当前约 574KB，硬上限 `600_000` bytes），保证干净环境可复现。
- Create `app/src/main/assets/device_names/device_models.json.gz`：按 Model 精确索引的紧凑 `Map<String,String>` 资产。
- Create `app/src/main/assets/device_names/device_models.meta.json`：源 URL、快照日期、源/产物 hash、条目与跳过计数、字节预算结果；运行时不读取。
- Create `app/src/main/java/com/chengzi/stayhifi/util/DeviceModelCatalog.kt`：纯 JVM 的有界 gzip JSON 解析、单飞预载、终态缓存和无 IO 查询。
- Create `app/src/main/java/com/chengzi/stayhifi/util/DeviceModelOverrides.kt`：带证据注释的精确人工 override。
- Create `app/src/main/java/com/chengzi/stayhifi/util/DeviceNameResolver.kt`：纯 JVM 四层裁决及属性键顺序。
- Modify `app/src/main/java/com/chengzi/stayhifi/util/DeviceNames.kt:12-38`：Android Context/Build/SystemProperties 薄适配器，保留现有同步 API 并增加预载状态流。
- Modify `app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt:24-35`：主线程仅注入 applicationContext，IO 协程预载一次；不得新增 `runBlocking`。
- Modify `app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt:225-238`：collect 机型名 StateFlow，预载完成后自动重组。
- Modify `app/build.gradle:12-18,97-98`：仅配置 instrumentation runner 与 androidTest 依赖。
- Create `app/src/test/java/com/chengzi/stayhifi/util/DeviceModelCatalogTest.kt`、`DeviceNameResolverTest.kt`：缓存和优先级纯 JVM 测试。
- Create `app/src/androidTest/java/com/chengzi/stayhifi/util/DeviceNamesInstrumentedTest.kt`：真实 Android 资产打包/解压与 framework SystemProperties 反射宿主测试。
- 既有调用点 `MainActivity.kt:650,916,953,1132` 与 `ApplyWorker.kt:101,247` 继续调用原 API，无需改动；这些诊断/档案路径不触发资产 IO。

---

### Task 1: 可复现的离线机型目录生成器

**Files:**
- Create: `tools/device_names/generate_catalog.py`
- Test: `tools/device_names/test_generate_catalog.py`
- Create: `tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz`
- Create: `app/src/main/assets/device_names/device_models.json.gz`
- Create: `app/src/main/assets/device_names/device_models.meta.json`

**Interfaces:**
- Consumes: Google CSV 四列 `Retail Branding / Marketing Name / Device / Model`，UTF-16 输入；spec §10 的精确样例和体积预算。
- Produces: CLI `generate_catalog.py (--url URL | --input PATH) --snapshot-date YYYY-MM-DD --source-sha256 HEX --output PATH --metadata PATH [--snapshot-gzip PATH]`；`--input` 支持 `.csv` 与 `.csv.gz`，`--snapshot-gzip` 仅在原始字节 hash 验证成功后写出固定 `mtime=0` 的版本化输入；资产 JSON 形状为按键排序的 `Map<String,String>`；函数 `format_display_name(retail_branding: str, marketing_name: str) -> Optional[str]` 与 `build_catalog(rows: Iterable[Dict[str,str]]) -> Tuple[Dict[str,str], Dict[str,int]]` 供单测使用。

- [ ] **Step 1: 写生成器失败测试。**

在 `test_generate_catalog.py` 用 `unittest` 和临时目录构造带 BOM 的 UTF-16 CSV，覆盖：`Oppo + Pad 4 Pro → OPPO Pad 4 Pro`；`Oppo + OPPO Pad 4 Pro` 不重复品牌；`ZTE + nubia Z80 → nubia Z80`；PHZ110 原始目录名为 `OPPO Find X7`；空 Marketing Name、等于 Model 的占位名、同 Model 同名归并、同 Model 异名剔除；Unicode 保留；错误 SHA 失败且不得写出 snapshot-gzip；同输入两次输出逐字节相同；`.csv` 与 `.csv.gz` 输入解码一致、snapshot-gzip 的 `mtime=0` 且不超过 `600_000` bytes；超限预算失败。

- [ ] **Step 2: 运行测试确认 RED。**

Run: `python3 -I -m unittest discover -s tools/device_names -p 'test_*.py' -v`

Expected: FAIL（生成模块/接口尚不存在）；记录真实错误，不把未执行记为 RED。

- [ ] **Step 3: 实现最小确定性生成器。**

仅用 Python 标准库。trim 并合并连续空白；OPPO 品牌规范化，通用品牌前缀去重，ZTE/nubia 不机械拼接；规范化后冲突的 Model 整体跳过。输出 UTF-8、紧凑分隔符、按键排序 JSON，gzip 固定 `mtime=0` 且不嵌入源文件名；metadata 不写当前生成时间。记录 source URL/date/SHA-256、源行数、写入/冲突/空值计数、JSON/gzip 字节数与 SHA-256，并执行 Global Constraints 的两个硬上限。

- [ ] **Step 4: 运行生成器单测确认 GREEN。**

Run: `python3 -I -m unittest discover -s tools/device_names -p 'test_*.py' -v`

Expected: 全部 PASS，尤其 `test_conflicting_models_are_skipped`、`test_brand_formatting_is_canonical_and_deduplicated` 和确定性测试。

- [ ] **Step 5: 从固定快照生成正式资产。**

Run:
```bash
python3 -I tools/device_names/generate_catalog.py \
  --url https://storage.googleapis.com/play_public/supported_devices.csv \
  --snapshot-gzip tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz \
  --snapshot-date 2026-10-27 \
  --source-sha256 a83be8f0ac7d963011394ead55416ce701dfe8b89bec89844ab3a24a254c3a23 \
  --output app/src/main/assets/device_names/device_models.json.gz \
  --metadata app/src/main/assets/device_names/device_models.meta.json
```

Expected: 源 hash 与固定快照一致；版本化 `.csv.gz` 不超过 `600_000` bytes；生成报告包含 PHZ110=`OPPO Find X7`、OPD2409=`OPPO Pad 4 Pro`、NX741J=`nubia Z80`，且两项资产字节数均低于硬上限。若上游已变化则停止并改用已提交的匹配快照，不静默换数据日期。

- [ ] **Step 6: 用保留快照验证逐字节可复现。**

Run:
```bash
python3 -I tools/device_names/generate_catalog.py \
  --input tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz \
  --snapshot-date 2026-10-27 \
  --source-sha256 a83be8f0ac7d963011394ead55416ce701dfe8b89bec89844ab3a24a254c3a23 \
  --output build/device-names/repro/device_models.json.gz \
  --metadata build/device-names/repro/device_models.meta.json
cmp app/src/main/assets/device_names/device_models.json.gz build/device-names/repro/device_models.json.gz
cmp app/src/main/assets/device_names/device_models.meta.json build/device-names/repro/device_models.meta.json
```

Expected: 两个 `cmp` 均 exit 0、无差异输出；`build/device-names/repro` 仅放复现产物且不提交，版本化 `.csv.gz` 快照必须随 Task 1 入库。

- [ ] **Step 7: 提交 Task 1。**

```bash
git add tools/device_names/generate_catalog.py tools/device_names/test_generate_catalog.py \
  tools/device_names/fixtures/supported_devices-2026-10-27.csv.gz \
  app/src/main/assets/device_names/device_models.json.gz \
  app/src/main/assets/device_names/device_models.meta.json
git commit -m "Task C1: 生成可复现离线机型目录" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: 有界解析与进程内单飞缓存

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/util/DeviceModelCatalog.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/util/DeviceModelCatalogTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Map<String,String>` gzip JSON；现有 `kotlinx-serialization-json:1.9.0`。
- Produces: `internal enum class CatalogLoadState { NOT_LOADED, READY, FAILED }`；`internal class DeviceModelCatalog(openAsset: () -> InputStream)`，暴露 `val state: CatalogLoadState`、`fun preload(): CatalogLoadState`（阻塞、仅 IO 调用）、`fun lookup(model: String): String?`（非阻塞、零打开/解析）。

- [ ] **Step 1: 写缓存失败测试。**

覆盖：合法 gzip 在 NOT_LOADED 时 `lookup` 为 null 且 opener 次数为 0；preload 后精确查询 OPD2409；未知键为 null；缺失资产、截断 gzip、合法 gzip 内坏 JSON、超过 `1_600_000` bytes 均进入 FAILED 且不抛给调用方；失败后重复 preload 不重读；多线程同时 preload 只调用 opener 一次且最终状态一致。

- [ ] **Step 2: 运行缓存测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.util.DeviceModelCatalogTest --rerun-tasks`

Expected: 编译 FAIL（类型尚不存在）；保存输出作为 RED 证据。

- [ ] **Step 3: 实现有界单飞缓存。**

`preload` 在调用者线程完成有界 GZIP/JSON 读取，用同步单飞保证 opener 至多一次；不得持锁执行 `lookup`，不得让 `lookup` 懒加载。最多读取 `1_600_001` 个解压字节以识别超限；成功后发布不可变 Map，任何打开/解压/解析异常统一转 FAILED，不记录资产内容。

- [ ] **Step 4: 运行缓存测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.util.DeviceModelCatalogTest --rerun-tasks`

Expected: 全部 PASS；并发用例 opener=1，损坏/超限用例无异常逃逸。

- [ ] **Step 5: 提交 Task 2。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/util/DeviceModelCatalog.kt \
  app/src/test/java/com/chengzi/stayhifi/util/DeviceModelCatalogTest.kt
git commit -m "Task C2: 增加机型目录有界单飞缓存" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: 四层精确裁决与人工纠正

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/util/DeviceModelOverrides.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/util/DeviceNameResolver.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/util/DeviceNameResolverTest.kt`

**Interfaces:**
- Consumes: `DeviceModelCatalog.lookup(model: String): String?` 与 Task 1 生成的目录值。
- Produces: `internal val DEVICE_MODEL_OVERRIDES: Map<String,String>`；`internal val MARKET_NAME_KEYS: List<String>`；`internal class DeviceNameResolver(buildModel: String, overrides: Map<String,String>, readProperty: (String) -> String?, catalog: DeviceModelCatalog)`，方法 `fun marketModel(): String`。

- [ ] **Step 1: 写四层优先级失败测试。**

以内存 gzip fixture 构造 READY/NOT_LOADED 的 `DeviceModelCatalog`，属性读取使用 fake lambda，覆盖：NX741J override=`nubia Z80 Ultra` 且压过属性和目录；PHZ110 override=`Find X7`；NX741JX 不得误中 NX741J；OPD2409 无属性时目录=`OPPO Pad 4 Pro`；DPD2438B 的 `ro.vivo.market.name` 压过目录；现代 OPlus 本地名优先、为空时读 enname、再回旧 `ro.oppo.market.name`；空白/异常/等于 Build.MODEL 的属性继续下层；全部 miss 回 Build.MODEL，Build.MODEL 空白时最后显示 `Unknown`。

- [ ] **Step 2: 运行 resolver 测试确认 RED。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.util.DeviceNameResolverTest --rerun-tasks`

Expected: 编译 FAIL（resolver/override 尚不存在）。

- [ ] **Step 3: 实现精确 override 与固定属性链。**

`DEVICE_MODEL_OVERRIDES` 仅加入 PHZ110=`Find X7`（固定目录展示口径）和 NX741J=`nubia Z80 Ultra`（用户提供纠正；注释链接 spec §2.2/§10，不声称 Google 已确认 Ultra）。属性顺序固定为 `ro.vivo.market.name`、`ro.product.marketname`、`ro.vendor.oplus.market.name`、`ro.vendor.oplus.market.enname`、`ro.oppo.market.name`，现代键注释链接 Global Constraints 的真实 getprop 样本。每层 trim，空白、异常和内部型号占位值继续下层；不做模糊匹配。

- [ ] **Step 4: 运行 resolver 测试确认 GREEN。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.util.DeviceNameResolverTest --rerun-tasks`

Expected: 全部 PASS，尤其 `test_invalid_property_values_fall_through`、精确 override、DPD2438B 属性优先与四个目标名称。

- [ ] **Step 5: 提交 Task 3。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/util/DeviceModelOverrides.kt \
  app/src/main/java/com/chengzi/stayhifi/util/DeviceNameResolver.kt \
  app/src/test/java/com/chengzi/stayhifi/util/DeviceNameResolverTest.kt
git commit -m "Task C3: 实现机型名四层精确裁决" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: Android 非阻塞预载、UI 更新与宿主验证

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/util/DeviceNames.kt:12-38`
- Modify: `app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt:24-35`
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt:225-238`
- Modify: `app/build.gradle:12-18,97-98`
- Test: `app/src/androidTest/java/com/chengzi/stayhifi/util/DeviceNamesInstrumentedTest.kt`

**Interfaces:**
- Consumes: `DeviceModelCatalog`、`DeviceNameResolver`、`DEVICE_MODEL_OVERRIDES`、`MARKET_NAME_KEYS`；资产路径 `device_names/device_models.json.gz`。
- Produces: 保留 `DeviceNames.marketModel(): String` 和 `DeviceNames.marketModelWithAndroid(): String`；新增 `DeviceNames.initialize(context: Context): Unit`、`DeviceNames.preload(): Unit`（阻塞、仅 IO）、`DeviceNames.marketModelWithAndroidFlow: StateFlow<String>`；`internal object AndroidSystemProperties { fun get(key: String): String? }` 供宿主测试。

- [ ] **Step 1: 写 Android 宿主失败测试。**

新增 `packagedCatalogAssetPreloadsAndContainsOpd2409`：用 targetContext 打开真实打包资产，preload 后 READY 且 OPD2409=`OPPO Pad 4 Pro`；新增 `systemPropertiesCanReadFrameworkModel`：反射读取 `ro.product.model` 等于当前宿主 `Build.MODEL`。宿主测试不断言任何 OEM 键必定存在；首屏同步查询不触发 opener 已由 Task 2 的 NOT_LOADED 纯 JVM 用例覆盖。

- [ ] **Step 2: 编译宿主测试确认 RED，并配置仅测试依赖。**

先在 `app/build.gradle` 配置 `testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"`、`androidTestImplementation 'androidx.test:runner:1.7.0'` 与 `androidTestImplementation 'androidx.test.ext:junit:1.3.0'`。

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:compileDebugAndroidTestKotlin --rerun-tasks`

Expected: 编译 FAIL（新 Android 接口尚不存在）；这只是 RED/编译证据，不宣称宿主行为已执行。

- [ ] **Step 3: 实现 Android 薄适配器与后台预载。**

`initialize` 仅保存 applicationContext；`preload` 调用同一 `DeviceModelCatalog.preload()` 并刷新 StateFlow。`marketModel()` 始终只走 override、反射属性和当前缓存快照，不等待、不打开资产；`AndroidSystemProperties.get` 保留现有反射并隔离异常。`StayHiFiApp` 在主线程 initialize，在 `Dispatchers.IO` launch 中 preload，不新增 `runBlocking`。

- [ ] **Step 4: 接入 Compose 状态流且保留既有调用点。**

HomeScreen 用 `collectAsState()` 读取 `marketModelWithAndroidFlow`，使预载后名称自动更新；`marketModelWithAndroid()` 的 Android 版本格式不变。MainActivity/ApplyWorker 既有调用不改，日志与档案仍不触发 IO，不修改其 codec/Trigger 逻辑。

- [ ] **Step 5: 编译并运行全量 JVM/静态构建验证。**

Run:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:compileDebugAndroidTestKotlin --rerun-tasks
JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks
JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew lint assembleDebug
```

Expected: 宿主测试编译成功；119 个基线测试与新增 JVM 测试全部 PASS；lint/assembleDebug 成功。报告新的真实测试数，不预写总数。

- [ ] **Step 6: 在有 Android 宿主时运行 connected 测试并核查首屏行为。**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:connectedDebugAndroidTest`

Expected（有已连接设备/模拟器）: 两个宿主测试 PASS，冷启动无资产解析导致的启动阻塞，预载后首页机型行可更新。无宿主时记 NOT RUN；只能声称 JVM/编译结果，不得声称 SystemProperties 或 AssetManager 已验证。

- [ ] **Step 7: 提交 Task 4。**

```bash
git add app/build.gradle \
  app/src/main/java/com/chengzi/stayhifi/util/DeviceNames.kt \
  app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt \
  app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt \
  app/src/androidTest/java/com/chengzi/stayhifi/util/DeviceNamesInstrumentedTest.kt
git commit -m "Task C4: 接入机型目录后台预载与宿主测试" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

## Final Verification

- [ ] 重跑生成器单测、固定快照逐字节复现、`./gradlew test --rerun-tasks`、`lint`、`assembleDebug`；命令全部使用 Global Constraints 的 JDK 27 前缀，预期全部通过并记录真实输出。
- [ ] 有签名环境时按现有流程运行 `assembleRelease`，确认 androidTest 依赖不进入发布包、无新增权限/网络组件；缺签名时明确 NOT RUN，不绕过签名要求。
- [ ] 对可用真机分别执行 `adb shell getprop ro.product.model` 及五个 MARKET_NAME_KEYS，记录原始键值；只有实际样本才能宣称该 ROM 的属性路径已验证，PHZ110 不得由 OnePlus Ace 3 样本外推。
- [ ] 外部验收精确检查 PHZ110=Find X7、OPD2409=OPPO Pad 4 Pro、NX741J=nubia Z80 Ultra；DPD2438B 在目录中制造一个不同 fake 值做 JVM 优先级测试，真机则核对 vivo 属性仍优先。缺任一样本时保留未验证结论。
- [ ] 检查 APK 中 gzip 资产不超过 `400_000` bytes、metadata 报告 JSON 不超过 `1_600_000` bytes；不得宣称覆盖所有型号，不得新增运行时下载、Root 或 codec/Trigger/aptX 改动。
