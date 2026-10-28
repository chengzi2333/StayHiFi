# StayHiFi 下一版实施计划索引

- 日期：2026-10-27
- 基线：v1.2.1(6)，master `d3adf81`
- Spec：`docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md`
- 范围：只包含非 aptX 工作流；aptX、手动档位、专属采集及相关 UI/档案字段全部 deferred。
- 基线验证：JDK 27 下 `./gradlew test --rerun-tasks` 实际执行 119 tests / 0 failures / 0 errors。

## 计划与执行顺序

| 顺序 | 计划 | 责任范围 | 依赖 |
|---|---|---|---|
| 1 | `docs/superpowers/plans/2026-10-27-codec-identity-realtime.md` | 只读 snapshot、系统编码器身份、共享 formatter、多段实况解析与四态显示 | 可基于 master 开始 |
| 2 | `docs/superpowers/plans/2026-10-27-capability-trigger-diagnostics.md` | 同条能力写入校验、参数不完整保护、TriggerGate、通用诊断、反馈日志脱敏 | 在 A 的 snapshot/identity/formatter 接口之后 |
| 可独立 | `docs/superpowers/plans/2026-10-27-device-names.md` | 厂商属性、离线机型表、人工 override、缓存与回退 | 不依赖 A/B/D，可独立实施和合入 |
| 3 | `docs/superpowers/plans/2026-10-27-experimental-root.md` | ProviderCoordinator、RootService、教程/设置入口、前台 anchor、切换与 Root 日志 | 在 A/B 的共享代码与 `FeedbackLogSanitizer` 之后 |

## 集成约束

1. A、B、D 都会触及 `MainActivity.kt`、`ApplyWorker.kt`、`ListenerService.kt` 或特权服务边界，**不得各自从旧 master 并行施工后强行合并**；按 A → B → D 在同一集成分支顺序实施/复审。
2. C 可独立实施，但若与 D 的教程/设置文案同时修改，应先合入 C，再由 D 做最终 UI 回归。
3. 所有计划只执行各自声明的文件与接口；跨计划接口以主 agent 复核后的版本为准，不允许实现者另起同名替代模型。
4. 默认 Shizuku/Stellar 行为、现有 9 字段写入 JSON、恢复默认参数和 192kHz selectable 边界不得因集成改变。
5. 不实现 aptX 自动策略、手动档位、aptX 专属采集或 Lossless 判断；通用诊断不得成为绕过该边界的通道。
6. 每个任务按各自 plan 做 TDD、独立审查和提交；最终统一跑全量测试、lint、debug/minified 构建，再评估外部机型与 Root 样本。
7. 本索引不是发布或 push 授权；外部 Root 与目标 ROM 未验证前，只能按实验能力表述。

## 统一构建命令

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew test --rerun-tasks
```

lint、debug 与 release 构建沿用同一 JAVA_HOME/PATH；release 必须使用项目既有签名配置，不为测试绕过签名要求。
