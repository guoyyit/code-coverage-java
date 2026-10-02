# code-coverage-java

一套自托管的 Java 代码覆盖率服务：从源码静态分析生成可覆盖元素清单，接收测试运行上报的覆盖事件，聚合生成逐文件覆盖率报告，并支持基线对比、增量分析、历史数据导入与重启恢复。

## 覆盖率语义

- **可覆盖元素分为两类**
  - `LINE`：可执行语句所在行（如方法调用、赋值、`return`/`throw`/`break`/`continue`、`if`/`for`/`while`/`switch` 等判断行）。字段声明、空块、纯括号不是可执行元素。
  - `BRANCH`：分支元素。`if` 产生 `if-true` / `if-false` 两个分支；`for`/`while` 产生 `loop-enter` / `loop-exit`；`do-while` 产生 `loop-continue` / `loop-exit`；`switch` 为每个 `case`/`default` 产生一个分支，没有 `default` 时额外产生“无匹配”默认分支；`catch` 产生 `catch-taken` / `catch-not-taken`；三元表达式产生一个 `ternary` 分支。
- **行覆盖率** = 命中的 LINE 元素数 / LINE 元素总数。
- **分支覆盖率** = 命中的 BRANCH 元素数 / BRANCH 元素总数。
- **整体覆盖率** = 命中的全部元素数 / 全部元素数；百分比四舍五入保留两位小数。空工程（无任何元素）覆盖率定义为 100%。
- 每行只产生一个 LINE 元素（按行去重），同一行的多个语句不重复计数。
- **批次内去重**：同一批次中同一元素被命中任意次都只计一次；事件允许乱序、重复、迟到（批次未结束前），聚合结果由有序集合保证确定性，与到达顺序无关。

## 元素 ID 与清单版本

- 元素 ID 由 `类型标记( L/B )-sha256(源文件相对路径|类型|行号|分支标签)` 的前 16 位构成，只要文件相对路径、行号与分支标签不变，ID 即稳定可复现。
- 每个文件有内容哈希 `sha256("content|相对路径|文件内容")`。
- 清单版本 ID 是所有文件（相对路径、内容哈希、元素 ID 序列）归一化排序后整体 SHA-256，**相同源码必定产生相同版本**。
- 每份报告都显式绑定一个 `manifestVersion`；历史批次始终绑定其创建时的版本，源码演进后：
  - 旧事件不会计入新清单中的元素（上报旧 ID 到新版本批次会被拒绝）；
  - 历史报告不会被改写，仍可按批次与旧版本聚合查询。

## 增量分析

调用分析接口扫描源码目录时：

- 以文件相对路径为键、内容哈希为准，内容未变的文件直接复用上一次的分析结果（持久化在分析缓存中），**不会重复解析**；
- 仅新增或修改过的文件才会重新调用解析器；删除的文件直接从清单移除；
- 分析结果产生新版本后，旧版本清单仍完整保留。

解析器基于 JavaParser（Java 17 语法级别），无法解析的文件会返回 400 与明确的文件路径和解析错误信息，该次分析不产生新版本。

## 批次生命周期

1. `POST /api/batches` 在当前清单版本上创建一个 `OPEN` 批次（可指定 `batchId`，不传则生成）。
2. `POST /api/batches/{id}/events` 上报事件，body 为 `{"events":[{"elementId":"..."}]}`。
   - 元素不属于该批次绑定的清单版本、事件缺少 `elementId` 或批次已结束时，请求被整体拒绝（HTTP 400/409）；
   - 返回本次新增命中数 `newlyAdded` 与累计命中数 `totalHits`。
3. `POST /api/batches/{id}/finish` 将批次置为 `FINISHED`，结束后拒绝新事件。重启后仍为 `OPEN` 的批次即“未完成批次”，可继续上报或随时结束。
4. 多批次整体报告为各批次命中集合的并集；每个批次也可单独查看报告。

保存基线时，当时的批次会被**冻结（frozen）**：批次与数据永久保留、仍可单独查询，但不再计入“当前整体覆盖率”，从而让基线对比反映基线建立之后的回归，而历史数据不被修改。

## 基线对比

- `POST /api/baselines`，body 为 `{"name":"..."}`，保存当前整体覆盖（含全部元素命中快照）为命名基线并冻结当前版本的批次。
- `GET /api/baselines/{name}/comparison` 对比基线与当前覆盖，结论（`verdict`）可区分：
  - `PASS`：覆盖率未较基线回退超阈值，且没有新增未覆盖元素；
  - `NEW_CODE_UNCOVERED`：基线清单中不存在的新增元素里有未覆盖元素；
  - `REGRESSION`：整体覆盖率下降幅度超过配置阈值；
  - `NEW_CODE_UNCOVERED_AND_REGRESSION`：两者同时发生。
- 对比仅按元素 ID 匹配：新增元素 = 当前清单中基线清单不存在的元素；被删除的元素不参与统计。相同输入的对比结果完全可复现。

## 历史数据导入

`POST /api/baselines/import`，body 为 `{"data":"<JSON 字符串>"}`，JSON 结构：

```json
{
  "formatVersion": "1.0",
  "manifestVersion": "<清单版本 ID>",
  "batchId": "imported-2026-01",
  "hitCount": 3,
  "hitElementIds": ["L-....", "B-....", "L-...."]
}
```

导入采用“先全量校验、后一次落盘”的事务语义，任何问题都**整体拒绝**，不会产生部分批次，失败原因包含中文说明：

- 不是合法 JSON、内容截断或存在多个 JSON 文档；
- `formatVersion` 不支持、缺少 `manifestVersion`/`batchId`/`hitElementIds`；
- `hitCount` 与实际元素条数不一致；
- `hitElementIds` 内有重复或空元素；
- `manifestVersion` 在本服务不存在（与当前已知清单不匹配）；
- 元素 ID 不属于该清单版本；
- `batchId` 已存在（HTTP 409）。

成功的导入生成一个 `FINISHED`、`origin=IMPORT` 的批次，随后即可在批次报告与整体报告中查询。

## 持久化与并发

- 所有状态写入 `coverage.data-dir`：`manifests/`（历史清单）、`batches/`（批次命中与状态）、`baselines/`（基线快照）、`cache.json`（文件分析缓存）、`state.json`（当前版本与源码目录）。写入采用临时文件 + 原子移动。
- 重启后清单历史、批次（含未完成状态）、命中数据与基线全部恢复。
- 服务对清单分析、事件上报、报告生成、基线操作使用同步控制；事件上报与报告查询可并发进行，报告始终对应一致的清单版本与某一时刻完整的命中集合。

## 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `coverage.data-dir` | `coverage-data` | 持久化数据目录，相对路径相对于进程工作目录 |
| `coverage.regression-threshold-percent` | `1.0` | 允许的整体覆盖率回落百分点上限，超过判为回退 |
| `coverage.fail-on-new-uncovered-code` | `true` | 新增元素未覆盖是否直接给出失败类结论；设为 `false` 时只在原因中提示 |

## HTTP 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/manifests/analyze` | 扫描 `sourceRoot`，增量重建并返回当前清单 |
| GET | `/api/manifests/current` | 当前清单版本 |
| GET | `/api/manifests/{versionId}` | 任一历史清单 |
| POST | `/api/batches` | 创建批次（body 可给 `batchId`） |
| GET | `/api/batches` | 全部批次（含状态、冻结标记） |
| GET | `/api/batches/{batchId}` | 批次详情 |
| POST | `/api/batches/{batchId}/events` | 上报覆盖事件 |
| POST | `/api/batches/{batchId}/finish` | 结束批次 |
| GET | `/api/reports/batches/{batchId}` | 单批次报告（逐文件、未覆盖清单） |
| GET | `/api/reports/aggregate` | 当前清单版本的整体报告 |
| GET | `/api/reports/aggregate/{versionId}` | 指定历史版本的整体报告 |
| POST | `/api/baselines` | 保存命名基线 |
| GET | `/api/baselines` / `/api/baselines/{name}` | 基线列表 / 详情 |
| GET | `/api/baselines/{name}/comparison` | 当前与基线的可复现对比 |
| POST | `/api/baselines/import` | 导入历史覆盖数据 |

错误响应统一为 `{"timestamp","status","error","message"}`，`message` 为可读的中文失败原因。

## 构建与测试

```bash
mvn test      # 28 个用例：元素识别、乱序重复事件、并发上报、增量重建、基线对比、导入失败、重启恢复、HTTP 端到端
mvn spring-boot:run
```
