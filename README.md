# 保税进口料件账册核销服务（Bonded Ledger）

面向跨境制造企业的**月度保税料件核销**服务：把报关申报与回执、仓储收发、生产投料、成品出区等来自不同系统的单证，
按**业务时间**落到同一本账册，按**进口批次**分配数量，校验
`期初 + 入库 − 投料 − 出区(含料) + 调整 = 结余`，
按月封存结果，并把差异、迟到更正、历史复现做成可追溯、可回放的闭环。

技术栈：Java 21 / Spring Boot 3.5 / PostgreSQL 16+ / springdoc-openapi（后端服务，含 Swagger UI）。

---

## 1. 一键启动

### 1.1 Docker Compose（推荐）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/actuator/health>

Compose 会启动 PostgreSQL 并设置 `SEED_DEMO=true`，首次启动自动跑一遍样例账期（ledger 代码 `DEMO-SEED`，期间 `2026-03`）。

### 1.2 本地直接运行（使用容器内预装 PostgreSQL）

```bash
# 依赖预装：PostgreSQL 18 在 127.0.0.1:5432，库/用户/密码均为 ccdev
export JAVA_HOME=/opt/jdk-25
/opt/maven/bin/mvn -q -B -DskipTests package
java -jar target/bonded-ledger-1.0.0.jar
```

或使用辅助脚本（可 `reset` 清空 schema）：

```bash
./scripts/dev-run.sh          # 保留已有数据启动
./scripts/dev-run.sh reset    # 清空 bonded_ledger schema 后启动
```

默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev?currentSchema=bonded_ledger`，可用环境变量覆盖：
`DB_URL`、`DB_USER`、`DB_PASSWORD`、`SERVER_PORT`、`SEED_DEMO`。
服务启动时自动执行 `src/main/resources/db/migration/schema.sql`（幂等），无需手工建表。

---

## 2. 测试账号与数据

- 本服务是**后端 API**，没有前端登录体系，因此没有用户名/密码账号。
  鉴权不在本题范围内；调用即用，账册通过 URL 中的 `{code}` 区分（例如 `DEMO-SEED`）。
- 数据库账号（本地开发/测试用，非生产配置）：`ccdev` / `ccdev`，库 `ccdev`，schema `bonded_ledger`。
- Docker Compose 下数据库账号：`bonded` / `bonded`，库 `bonded`。

---

## 3. 核心概念

| 概念 | 说明 |
| --- | --- |
| 单证版本 | 每次收到的原文都入库（含原文 JSON、来源系统、`sourceVersion`、业务时间、`payloadHash`）。重发同版本是幂等回放，新版本**逻辑替换**旧版本但绝不覆盖。 |
| 有效期映射 | 料号映射 `material_alias`、单位换算 `unit_conversion`、加工关系 `processing_relation` 都带 `validFrom/validTo`。计算时按**单证业务日期**取当时生效的映射，**不用当前映射倒算整月**。 |
| 双池守恒 | 每个物料分别维护 **库存池**（期初+入库−投料+调整=结余）与 **在制池**（期初在制+投料−出区含料=结余在制）。 |
| 批次分配 | 投料/出区/负向调整都生成**逐笔批次分配**（FIFO 或指定批次），每笔不得超过该批次可用量；不足量变成 `*_SHORTAGE` 差异，**不会出现负库存或超耗**。 |
| 月度封存 | 每次关账写一个不可变版本 `period_close`，保存**输入快照**、算法版本、输入/结果哈希。新版本只追加并引用旧版本，旧版本可复现。 |

### 3.1 单证类型

| docType | 作用 | 是否影响余额 |
| --- | --- | --- |
| `OPENING_BALANCE` | 期初库存 | 是（仅首期） |
| `DECLARATION` | 进口报关申报（法定入库依据） | 是 |
| `DECLARATION_RECEIPT` | 海关回执，校验申报是否已获回执 | 否（校验） |
| `WAREHOUSE_RECEIPT` | 仓库存收货单，校验申报与实收差异 | 否（校验） |
| `ISSUE` | 生产投料（库存→在制） | 是 |
| `OUTBOUND` | 成品出区（按加工关系核销在制含料）或料件直接出区 | 是 |
| `ADJUSTMENT` | 有原因的人工调整（正数入库、负数核减） | 是 |
| `STOCKTAKE` | 盘点，校验账实差异（本身不入账） | 否（校验） |
| `VOID` | 作废某版本 | 通过替换链生效 |

---

## 4. 主要接口

完整清单见 Swagger UI。所有路径前缀 `/api/v1`。

### 4.1 账册与口径

```bash
# 建账册
curl -X POST localhost:8080/api/v1/ledgers -H 'Content-Type: application/json' \
  -d '{"code":"E2E","name":"示例账册","zone":"Asia/Shanghai"}'

# 物料（规范料号 + 基本单位）
curl -X POST localhost:8080/api/v1/ledgers/E2E/materials -H 'Content-Type: application/json' \
  -d '{"code":"AL-6061","name":"铝 6061","baseUnit":"KG"}'

# 有效期料号映射（改名请新增一条，并给旧条目设置 validTo，绝不修改历史）
curl -X POST localhost:8080/api/v1/ledgers/E2E/aliases -H 'Content-Type: application/json' \
  -d '{"sourceSystem":"ERP","sourceItemCode":"AL6061-OLD","material":"AL-6061","validFrom":"2026-01-01","validTo":"2026-02-28"}'
curl -X POST localhost:8080/api/v1/ledgers/E2E/aliases -H 'Content-Type: application/json' \
  -d '{"sourceSystem":"ERP","sourceItemCode":"AL6061-NEW","material":"AL-6061","validFrom":"2026-03-01"}'

# 单位换算（TON→KG）
curl -X POST localhost:8080/api/v1/ledgers/E2E/conversions -H 'Content-Type: application/json' \
  -d '{"material":"AL-6061","fromUnit":"TON","factor":"1000","validFrom":"2026-01-01"}'

# 加工关系（1 PCS 成品含 0.5 KG 料件）
curl -X POST localhost:8080/api/v1/ledgers/E2E/processing-relations -H 'Content-Type: application/json' \
  -d '{"outputMaterial":"FG-HOUSING","inputMaterial":"AL-6061","qtyPerOutput":"0.5","validFrom":"2026-01-01"}'
```

### 4.2 单证接入（保留原文）

元数据放请求头，**请求体就是源系统原始 JSON**，原文原样入库：

```bash
curl -X POST localhost:8080/api/v1/ledgers/E2E/documents \
  -H 'Content-Type: application/json' \
  -H 'X-Doc-Type: DECLARATION' \
  -H 'X-Source-System: CUSTOMS' \
  -H 'X-External-Id: DEC-1' \
  -H 'X-Source-Version: 1' \
  -H 'X-Business-Time: 2026-03-02T09:00:00+08:00' \
  -d '{"declarationNo":"DEC-1","lines":[{"itemCode":"AL6061-NEW","unit":"KG","quantity":1000,"batchNo":"B-2026-03"}]}'
```

可选头：`X-Idempotency-Key`、`X-Correction-Reason`、`X-Supersedes-Document-Id`、`X-Void-Of-Document-Id`。
不便设置头时可用 `POST .../documents/envelope`，把元数据和 `payload` 放进同一个 JSON。

原文与版本查询：

```bash
curl 'localhost:8080/api/v1/ledgers/E2E/documents/12'                 # 单个版本原文
curl 'localhost:8080/api/v1/ledgers/E2E/documents/history?sourceSystem=CUSTOMS&docType=DECLARATION&externalId=DEC-1'
```

### 4.3 关账与追溯

```bash
# 关账（MATERIALIZED：物化逐笔批次分配；REBUILD：只存快照，查询时重建；AUTO：按单证量自动选择）
curl -X POST localhost:8080/api/v1/ledgers/E2E/periods/2026-03/close \
  -H 'Content-Type: application/json' \
  -d '{"mode":"MATERIALIZED","closedBy":"关务员","waitForLock":true}'

# 结果、批次分配、差异
curl localhost:8080/api/v1/ledgers/E2E/closes/1
curl localhost:8080/api/v1/ledgers/E2E/closes/1?includeAllocations=true
curl 'localhost:8080/api/v1/ledgers/E2E/closes/1/allocations?material=AL-6061'
curl localhost:8080/api/v1/ledgers/E2E/closes/1/inventory          # 批次库存 + 在制
curl localhost:8080/api/v1/ledgers/E2E/closes/1/trace/AL-6061      # 余额→支持它的单证
curl 'localhost:8080/api/v1/ledgers/E2E/discrepancies?period=2026-03&status=OPEN'

# 复现历史结果（用快照+算法版本重算，比对存储哈希）
curl -X POST localhost:8080/api/v1/ledgers/E2E/closes/1/reproduce

# 期间内所有关账版本
curl localhost:8080/api/v1/ledgers/E2E/periods/2026-03/history
```

### 4.4 差异处理

```bash
curl -X POST localhost:8080/api/v1/ledgers/E2E/discrepancies/1/resolve \
  -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED","resolution":"RECEIPT_FILED","note":"采购补齐回执","resolvedBy":"关务员","adjustmentDocumentId":null}'
```

差异类型包括：`DECLARED_VS_RECEIVED`、`UNACKNOWLEDGED_DECLARATION`、`ISSUE_SHORTAGE`、
`OUTBOUND_SHORTAGE`、`OUTBOUND_CONTENT_SHORTAGE`、`CONTENT_EXCEEDS_ISSUE`、`STOCKTAKE_VARIANCE`、
`UNMAPPED_ITEM`、`UNMAPPED_UNIT`、`MISSING_PROCESSING_RELATION`、`ADJUSTMENT_WITHOUT_REASON`、
`PRIOR_PERIOD_NOT_CLOSED` 等。

---

## 5. 验证方式

### 5.1 自动化测试（推荐，21 个用例）

```bash
export JAVA_HOME=/opt/jdk-25
/opt/maven/bin/mvn test
```

- `LedgerCalculatorTest`（10 个，纯函数、无需数据库）：
  并发领用不超耗、双池数量守恒、迟到更正不影响已冻结结果、申报未回执仍入账但标记差异、
  申报/实收差异、FIFO 跨批次、未映射料号不静默丢弃、盘点差异、出区含料超投料告警、缺加工关系告警。
- `LedgerIntegrationTest`（11 个，连真实 PostgreSQL）：
  关账幂等与可复现、**同期间并发关账被串行化**、两张工单领用同一批料不超入库、
  **迟到更正产生引用新版本号的调整结果且旧报送余额仍可复现**、REBUILD 与 MATERIALIZED 余额一致、
  **期间内料号改名按业务日期分别解析、不用当前映射倒算**、跨期结转按结存量而非原入库量、
  **作废单证从重算中剔除且旧报送余额仍可复现**、单位换算、差异处理留痕、批次库存/在制报表。

### 5.2 可回放样例

服务运行后：

```bash
./scripts/replay-sample.sh 2026-03
```

会新建一个独立账册，完整重放：期初 → 申报+回执+短收 → 月中料号改名 → 两张工单争抢同一批次 →
成品出区 → 首次关账 → 关账后海关更正申报 → 追加调整版本，并自动断言：

- 数量守恒（逐物料 `期初+入库−投料+调整=结存`，在制池同理）；
- 两工单合计投料不超过入库；
- 调整版本引用被替换的来源版本（`SUPERSEDED`）；
- **更正前报送的余额仍可复现**（`v1.reproduced=true`），旧版本保留为 `SUPERSEDED`。

退出码 0 表示全部断言通过。也可以直接调用：

```bash
curl -X POST localhost:8080/api/v1/samples/month-end-replay \
  -H 'Content-Type: application/json' -d '{"period":"2026-03"}'
```

### 5.3 手工并发验证（可选）

启动后并发发起同一期间的关账，观察只产生一个版本，其余请求返回复用的同一结果或 `409 CLOSE_IN_PROGRESS`：

```bash
for i in $(seq 1 8); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    localhost:8080/api/v1/ledgers/E2E/periods/2026-03/close \
    -H 'Content-Type: application/json' -d '{}' &
done; wait
curl localhost:8080/api/v1/ledgers/E2E/periods/2026-03/history
```

---

## 6. 关键设计取舍

### 6.1 串行化可用量分配

- 每次关账在同一事务内先取 `pg_advisory_xact_lock(ledger, period)`，**同期间并发关账被串行化**；
  `waitForLock=false` 时用 `pg_try_advisory_xact_lock` 快速返回 `409`。
- 分配本身在单事务内按业务时间/单证顺序做 FIFO 批次扣减，**每笔以批次可用量为上限**，
  因此“两张工单并发领用同一批料”在计算结果上不会让总耗用超过入库；不足量变成差异。

### 6.2 迟到更正与历史复现

- 每次关账把**输入快照**（单证版本+哈希、开账批次、所用映射行、算法版本）整体存为 JSON。
- 关账后收到撤销/更正：新版本单证 `sourceVersion+1` 并引用 `supersedesDocumentId`；
  重新关账（`allowOverwrite=true`）追加新版本，`close_change` 记录 `SUPERSEDED`/`VOIDED`/`LATE_ARRIVAL`
  并引用新旧单证 id；旧版本标 `SUPERSEDED` 但**不删除**。
- `POST /closes/{id}/reproduce` 用快照+算法版本重算，逐物料比对报送值与重算值并比对结果哈希；
  旧版本因此**永远可复现**。

### 6.3 有效期口径，不用当前映射倒算

- 料号、单位、加工关系都按 `validFrom/validTo` 在**单证业务日期**解析（`SnapshotFactory` 内按日期索引）。
- 期间内改名时，月初单证用旧码、月末单证用新码，二者都落到同一规范料号，数量不被“当前映射”整体改写。
- 快照记录本次用到的映射行，便于审计。

### 6.4 物化 vs 重建（按单证量与查询时限取舍）

| 模式 | 存什么 | 优点 | 代价 |
| --- | --- | --- | --- |
| `MATERIALIZED` | 快照 + **逐笔批次分配关系** | 审计/追溯查询快 | 存储更大；更正需整期重算并传播 |
| `REBUILD` | 快照 + 算法版本（不存逐笔关系） | 存储小、更正传播轻 | 大账期追溯查询更慢（每次重建） |

- 接口 `mode` 选择；同一期间的新版本**沿用首版模式**，避免审计口径漂移。
- `mode=AUTO` 按当月单证量与阈值自动选择：单证量超过 `ledger.materialization.auto-threshold-documents`
  （默认 500，即大账期、追溯查询慢）走 `REBUILD`（省存储、更正传播轻），否则走 `MATERIALIZED`。
- 单证量大、关务查询时限紧 → `MATERIALIZED`；单证量小、更正频繁 → `REBUILD`。

---

## 7. 目录结构

```
src/main/java/com/customs/ledger/
  api/         REST 控制器（账册/接入/关账/差异/样例）
  calc/        快照模型、快照构建、确定性计算引擎
  books/       关账服务、结果查询、批次库存
  diff/        差异登记与处理
  domain/      账册、物料、映射、单证仓储
  ingest/      单证接入与原文/口径解析
  rating/      有效期映射解析
  replay/      历史复现、可回放样例、样例种子
  config/      配置与属性
src/main/resources/db/migration/schema.sql   幂等建表
src/test/java/com/customs/ledger/            21 个测试
scripts/                                     本地启动与样例回放
Dockerfile / docker-compose.yml
```
