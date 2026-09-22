# FINAL-20260922-1：R1 密钥存储整改交付记录

## 范围与基线

用户指定基线：`audit/reader-stream-r2-20260922@5e10deef341cab57d61b06f090721897b78f82e9`。本批直接补齐原整改中的运行时密钥存储缺口，不合并 main，不引入数据库、Redis、新供应商或收费测试。不把旧报告的 480 项通过当作本批证据。

**已测试功能提交：** `1764a45e11047cfeab905440cccd10e77983ab99`。相对用户指定基线共 20 个变更文件，新增 20 项 Java 测试；本次更新仅将已取得的验证结果补入这份报告。完整执行记录：[CI 35710661815](https://github.com/litany-lty/book-html-studio/actions/runs/35710661815)。本报告不提前声称后续提交的 CI 已通过。

本批不是整份 FINAL 规范验收完成。已实现 F02 的秘密隔离主体及 F01 的 DTO/日志/前端继续加固；完整 R0 盘点、R2–R6 剩余差异映射和 R7 发布矩阵仍需实施/验收。当前不能按“最终全部整改完毕”发布。

## 实际代码

- `SecretStore` 提供 ENV_ONLY、ENCRYPTED_FILE、resolve/put/remove/isConfigured，凭据引用仅在服务端使用。原有内部 State 仍持有效凭据供客户端调用，但不再作为落盘 DTO，其 toString 固定脱敏。
- ENV_ONLY 为默认模式：进程环境中的现有供应商配置作为只读秘密来源；普通设置只保存 `env:v1:<slot>` 引用。界面不能修改或清除环境变量，仍可更改非秘密配置。不能因为已有密钥就扩大外发授权。
- ENCRYPTED_FILE 使用 Java AES-256-GCM，每份不可变凭据记录独立随机 96-bit nonce、128-bit tag。AAD 绑定格式版本、外部部署身份、完整 secretRef、keyId；普通设置仅保存随机引用。主密钥仅外部注入，不写磁盘、不自动生成、不在页面输入。
- `.settings/settings.json` 为 schemaVersion=2。五个明文凭据字段全部移除，增加恰好五项 secretRefs。凭据不通过 Base64 等可逆编码放回普通 JSON。
- 更新时：准备不可变密文并读回验证 → 原子替换 settings.json（唯一发布点）→ 清理未引用密文并完成运行时快照更新。发布前失败只删本次新准备的记录，旧设置与旧凭据继续有效。清理失败不把已经成功的保存报告为失败；安全 DTO 中 `cleanupPending` 提示待维护。
- 启动迁移前通过 Spring `DependsOn(bookStore)` 先获取现有数据目录单写者租约。旧 State 通过完整字段校验后才迁移，保持其 revision。普通配置损坏、密文损坏、主密钥或 AAD 错误时停止启动，不覆盖旧文件、不静默使用别的环境凭据。
- 保留设置修订检查、活动工作租约、CSRF/同源/no-store 等既有约束。GET/PUT 输出只有配置状态和非秘密数据，不提供 secretRefs、密钥或主密钥。
- 新前端使用 `secretUpdates.<slot>.value` 或 `clearSecret:true`。缺失/空串保留，显式清除有效，掩码/空白无效。旧嵌套秘密字段保留兼容适配；同时使用两种写法对同一字段更新会拒绝。保存完成、失败、读回失败、关闭设置页后清空密码框及临时请求对象；JavaScript 字符串内存不保证可安全擦除。
- POSIX 目录 0700、文件 0600；非 POSIX 使用 owner-only ACL，无法安全保护或原子替换则失败。拒绝符号链接与非普通文件；凭据文件 32KiB、普通设置 64KiB、密文目录记录 64 项硬上限。目录 fsync 在支持的平台执行，不承诺所有平台掉电后都具有同样耐久性。

## 升级前必读

**旧版在网页保存过密钥时，不要直接无准备地覆盖运行程序。** 停止新增任务并等待在途自然结束，停止旧进程；将书籍/人工稿与 `.settings` 做一致性备份。旧明文设置备份必须另行加密、留在仓库外。新程序不自动轮换供应商账号密钥，也不能保证擦除 SSD/历史备份中的旧明文。

### 方案 A：环境注入

设置 `BOOK_SECRET_STORE_MODE=ENV_ONLY`，通过既有供应商环境变量提供秘密。首次非秘密保存只持久化引用。存在旧明文配置时，每个非空旧秘密必须与注入值一致才能迁移；不一致时停止，绝不清空旧文件。需要改变已注入值时由部署环境管理，重启后生效。ENV_ONLY 不允许网页更新或清除密钥。

### 方案 B：页面保存凭据

在仓库外安全注入：

```text
BOOK_SECRET_STORE_MODE=ENCRYPTED_FILE
BOOK_SECRET_MASTER_KEY=<随机32字节的标准Base64，不能使用示例/测试常量>
BOOK_SECRET_DEPLOYMENT_ID=<稳定、非秘密的部署标识>
BOOK_SECRET_KEY_ID=primary
```

可以在受控终端用 `openssl rand -base64 32` 生成一次主密钥，直接转存部署秘密管理设施；不要把输出发到聊天、日志、工单或提交中。主密钥与部署身份应安全备份，主密钥不能与普通配置/密文备份放在同一导出包。丢失主密钥将无法恢复密文中的秘密，书籍与人工稿文件本身未被本批加密。

新程序启动时验证旧字段，先写密文并验证，再替换普通设置。成功后原 settings.json 不再含明文；代码不会创建明文 .bak。错误主密钥、错误部署身份、权限不安全或迁移失败会拒绝启动，并保留原设置供受控恢复。本批没有实现“秘密锁住但仍启动本地只读 UI”的降级模式。

**不要直接修改主密钥/部署身份/keyId 当作轮换。** 本批记录 keyId 并验证身份，但尚未提供多密钥在线轮换命令。轮换应另行实施停机重加密和验证流程；旧密文恢复必须有匹配的原主密钥、部署身份和 keyId。

### 验收与回退

启动成功后，在网页确认 configured 状态，不能以此推断供应商连通已验证；检查普通设置只有 schemaVersion/secretRefs/非秘密字段。勿把真实设置内容粘入报告。真实云冒烟仍需单独授权和硬预算。

迁移到 schemaVersion=2 后，不可回退到只认识明文 State 的旧二进制。应保留本批秘密存储读写能力，通过关闭可选增强降级。备份恢复应包含同一时点的 settings.json 和其引用的全部 `.secret` 文件，另行提供匹配的外部主密钥。没有自动合并/重放旧付费任务。

## 实测结果与精确证据

### 功能提交的完整 CI

以下结果全部绑定 `1764a45e11047cfeab905440cccd10e77983ab99`，不是旧基线结果。CI 使用 Ubuntu、Temurin Java 17.0.20、Node 22、Python 3.12；测试工作区干净。执行记录为 [run 35710661815 / job 106690204699](https://github.com/litany-lty/book-html-studio/actions/runs/35710661815/job/106690204699)。

| 检查 | 实际结果 |
|---|---|
| Java 17 完整 `mvn --batch-mode verify` | 88 个测试套件，500 项测试：498 通过、0 失败、0 错误、2 跳过；构建成功 |
| 新增 Java 回归 | SecretStoreTest 7 项、SettingsPersistenceTest 9 项、SettingsControllerTest 新增 4 项均通过 |
| 原有 JS / Python 检查与新增凭据控件探针 | CI 步骤全部通过；凭据控件探针 19 项断言通过 |
| 当前受控文件扫描 | 641 个文件，零规则命中 |
| 可达 Git 历史扫描 | 1,216 个唯一历史 blob，零规则命中 |
| 真实设置页：ENV_ONLY | 密钥控件只读且空白；非秘密保存及关闭重开通过；故障注入后清空输入；禁用字段内的模拟旧值未进入 PUT |
| 原有真实阅读浏览器回归 | 第 8 页先派发、第 9 页随后并行；二者在途时第 27 页进入保留槽；进度位于保存右侧，390px 无横向溢出，完成后隐藏 |
| 浏览器 JavaScript 页面异常 | 0 |
| 构建清单与测试证据归档 | 成功，测试 SHA 与源码/构建资源身份已记录 |

两项跳过：`QwenTocRecoveryServiceTest.realDirectoryFixturesHaveIndependentDividersWhenAvailable`（原有样本前置条件未满足），`Page25CachedReplayTest.replaysSavedQwenAndMiniMaxResponsesWithoutNetwork`（私有缓存响应不存在）。没有把跳过写成通过。

首轮 `4754a394e9678e06a4601014909c56a915ababff` 的 CI 编译成功，但五个既有测试在默认 ENV_ONLY 模式下修改凭据而报错。修正仅为 `PageAttemptLifecycleTest` 和 `ReadingWindowServiceTest` 注入显式合成加密存储；原测试正文、授权/并发/幂等/人工稿保护断言保持不变，未削弱生产 ENV_ONLY 策略。上述最新完整 CI 已验证修正。

浏览器使用真实前端、HTTP、后端队列与存储，但 OCR 为隔离的 Mockito 合成实现；设置页故障通过浏览器路由注入 500。未调用收费供应商，不能将这些耗时或文字结果当成真实模型表现。已查看设置页桌面截图与 390px 阅读处理态截图；截图不等于完整 UX 或用户研究验收。

### 构建与归档身份

```text
testedCommit: 1764a45e11047cfeab905440cccd10e77983ab99
JAR: book-html-studio.jar
JAR bytes: 41892113
JAR SHA-256: fe76090eefebb8a0c9675bd8c98e7fd5b11bb2c774789f422bbae414b2892dde
Evidence artifact ID: 10686226819
Evidence artifact: jev-test-evidence-1764a45e11047cfeab905440cccd10e77983ab99
Evidence ZIP SHA-256: f2bd676ab9722e6d10e5a0bce3c75bea99ec7ff29cf5fa68afe2b9d1fc0e1211
```

证据归档包含 JUnit XML/TXT、浏览器截图/results.json、脱敏扫描报告、测试 SHA 与构建 manifest；不包含可供直接安装的 JAR。本批已下载归档并校验 ZIP SHA-256，与 GitHub artifact digest 一致。扫描器另记 666 个二进制文件/对象；二进制容器、未知编码、不可访问引用及不可达历史没有被认证为无秘密。

### 本地独立检查

本地没有 Maven/项目依赖，完整 Maven 结论来自上面的 GitHub CI。另在本地使用 `javac --release 17` 编译纯 Java 存储模块、JDK21 运行独立探针：28 项断言通过；Node 凭据控件探针 19 项断言通过；设置 JS / 新模块语法与浏览器 Python 语法检查通过。局部探针不替代完整 JUnit 或真实浏览器。

新增测试覆盖 nonce/完整性/引用调包/错误主密钥与 AAD/路径/容量、旧明文迁移、原子发布前磁盘失败、旧引用不被误删、发布后清理失败、ENV_ONLY、schema 混入明文、重复键、超限输入及 API 显式更新。原设置测试更新为“不含明文且能凭引用重启恢复”，保存/重启/清除场景保留。

独立探针复现（不会读取实际密钥）：

```bash
mkdir -p /tmp/book-secret-probe-classes
javac --release 17 -d /tmp/book-secret-probe-classes \
  src/main/java/studio/bookhtml/config/{SecretStore,EnvironmentSecretStore,PrivateSettingsFiles,EncryptedFileSecretStore}.java \
  scripts/verification/SecretStoreProbe.java
java -cp /tmp/book-secret-probe-classes studio.bookhtml.config.SecretStoreProbe
node scripts/verification/probe_settings_secrets.mjs
mvn --batch-mode verify
```

## 分支与合并状态

所有功能代码已正常快进推送到用户指定分支，没有 force push。期间 main 有独立的并行改动，PR #3 显示合并冲突；本批保留那些改动，没有擅自合并、覆盖或调整 main。为避免 PR 无法生成合并引用时失去验证，增加了指定分支 push 触发，运行与 PR 相同的完整 CI 门禁。分支本身 CI 通过，不等于已与最新 main 集成通过。

## 未完成门禁与边界

本批仅补 R1 存储/DTO/凭据交互，不声明 SEC 全组通过。ENCRYPTED_FILE 模式的完整浏览器操作、Windows NTFS ACL、macOS、强制掉电恢复、外部主密钥丢失演练、历史制品/导出 canary 扫描仍需专项证据。当前树和可达历史扫描已执行，但二进制、不可达历史、旧共享副本不因规则零命中而绝对安全。数据目录内跨文件掉电耐久性没有被故障注入单测完全证明。

未在本批实现：所有计费接口统一能力认证、非 loopback 部署门禁、完整 CSP/导出兼容、R2 权威持久 attemptSeq/提交意图日志、R3 唯一执行引擎与跨入口发送账本的全部要求、R4 持久增量索引与新 bootstrap、R5 完整 BookContentProfile/ContextSnapshot 契约、R6 V3 固定计划进度和全部交互矩阵、R7 真实数据性能验收。基线已有部分实现，后续必须逐项对照源码确认，不从这份未完成列表推断它们全部不存在。

**交付结论：R1 本批代码已推送，精确功能 SHA 的完整 CI 通过；未合并 main，整份 FINAL 规范仍未完成发布验收。**
