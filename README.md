# Java 代码覆盖率服务

一套基于源码静态分析的轻量级代码覆盖率服务：从 Java 源码生成**可覆盖元素清单**，
接收测试运行批次上报的覆盖事件，聚合成行/分支覆盖率报告，支持多批次合并、
基线对比、增量分析、历史数据导入与重启恢复。

服务只依赖 JDK 与 Spring Boot Web，覆盖率上报方可以是任意测试执行器（探针、
插桩插件或离线文件），只要按元素 ID 上报命中事件即可。

## 覆盖率语义

- **可覆盖元素（CoverableElement）**：最小可覆盖单位，分两类：
  - `STATEMENT`：可执行语句，按源码行聚合；
  - `BRANCH`：分支的单个出口（arm），例如一个 `if` 产生两个分支元素。
- **行覆盖（line coverage）**：已命中的语句元素数 / 语句元素总数 × 100%。
    一行上只要任一语句元素被命中，该行即视为已覆盖；逐条语句仍单独计数，
    可在报告的 `lines[].elementIds` 中列出。
- **分支覆盖（branch coverage）**：已命中的分支元素数 / 分支元素总数 × 100%。
- 覆盖率保留两位小数；分子为 0 时覆盖率记为 `0.0`。
- 报告中未覆盖的行与分支可逐条列出（`lines` / `branches` 中 `covered=false`）。

## 元素识别规则

分析器（`JavaSourceAnalyzer`）不使用完整 Java 语法解析，而是采用确定性的
词法扫描，规则固定、对相同内容输出完全一致：

1. 先剥离注释（`//`、`/* */`）与字面量（字符串、字符、文本块）内容，
   保留换行以维持原始行号。注释与字符串中的 `if/;/return` 等不会被识别。
2. **可执行语句行**：
   - 含 `;` 的赋值、自增、方法调用、`return/throw/break/continue` 等；
   - 纯声明（`int x;`、抽象/接口方法签名、`package/import`）、注解不算；
   - `case X:` / `default:` 标签行与显式初始化块算可执行；
   - 方法/构造器声明行不算语句；
   - `if/for/while/switch/...`、`else`、`do` 等控制头行算可执行。
3. **分支元素**（每个出口一个 `BRANCH` 元素）：
   - `if`、`while`、`for`、`do`：各 2 个出口；
   - 三元运算符 `cond ? a : b`：2 个出口；
   - 短路逻辑 `&&`、`||`：每个运算符 2 个出口；
   - `switch`：`case` 数 + 1 个出口（含贯穿到块结束的出口）。
4. **元素 ID（稳定且内容敏感）**：
   - 语句：`STMT:<相对路径>:<行号>:<行签名>`
   - 分支：`BR:<相对路径>:<行号>#<文件内序号>:<行签名>`
   - 行签名是“注释/字面量剥离后、压缩空白的行文本”的 SHA-256 前 10 位。

   因此同一份代码每次分析得到的 ID 完全一致；而某行代码被修改后，该行元素会
   获得新 ID，成为一个**新元素**——旧版本的事件不可能计入新版本元素。

## 清单版本与增量重建

- 每个文件按内容计算 SHA-256 哈希；清单版本 `version` 由“源目录 + 排序后的
  `相对路径:文件哈希` 列表”再次哈希得到，确定且可复现。
- 再次分析时，内容哈希未变的文件直接复用旧 `FileManifest`，**不重复分析**；
  只有新增/修改的文件会重新扫描，删除的文件自然消失。
- 所有历史版本清单都会持久化保存，历史批次与历史报告始终可查。

## 事件、批次与报告

- 批次（batch）代表一次测试运行，创建时绑定当前清单版本；状态为 `OPEN` 或
  `COMPLETED`。未完成（`OPEN`）批次可随时识别，重启后仍是 `OPEN` 并可继续上报。
- 事件允许**乱序、重复、迟到**：同一批次内同一元素被命中多次只计一次
  （TreeSet 去重），事件顺序与时间戳不影响聚合结果。
- 事件的元素必须属于该批次绑定的清单版本，否则整事件拒绝；批次完成后到达的
  迟到事件被拒绝，历史报告不会被改写。
- 每份报告严格对应一个清单版本；跨版本批次禁止合并。
- 多批次合并取命中元素的并集；批次 ID、行、分支均排序输出，同状态下生成的
  报告（含 `reportId` 与 `generatedAt`）逐字节确定，可复现。

## 基线对比

- 基线保存某清单版本下一份完整合并报告。
- 对比结论（可区分、确定）：
  - `MATCHED`：行/分支覆盖率均未上升，且没有新增未覆盖元素；
  - `IMPROVED`：覆盖率上升且无新增未覆盖元素；
  - `NEW_CODE_UNCOVERED`：出现了基线中不存在、当前也未命中的元素，且整体覆盖率
    回退未超过阈值；
  - `REGRESSED_OVER_THRESHOLD`：行覆盖率（或分支覆盖率）下降幅度严格超过
    配置阈值（百分点）。该结论优先级最高，新增未覆盖元素仍会同时列出。
- 阈值通过 `coverage.regression-threshold-percent` 配置（默认 1.0 个百分点）。

## 历史数据导入

`POST /api/imports` 上传 JSON 文件（multipart 字段 `file`），格式：

```json
{
  "formatVersion": "1.0",
  "sourceRoot": "/abs/path/to/src",
  "batchId": "historical-001",
  "manifestVersion": "m2f3...",
  "events": [
    { "timestamp": 1, "elementIds": ["STMT:Foo.java:3:ab12...", "BR:Foo.java:5#0:cd34..."] }
  ],
  "batchStatus": "COMPLETED"
}
```

导入采用“**先全量解析校验、后整体落盘**”：JSON 语法错误或内容截断、
缺少必填字段、元素不属于当前清单、清单版本/源目录不匹配、批次已存在等，
都会整体拒绝（HTTP 400，`message` 给出明确原因），不会产生任何批次或命中数据。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/manifests/analyze?sourceRoot=...` | （增量）分析源码目录 |
| GET  | `/api/manifests/{version}/elements` | 列出清单版本的全部元素 |
| POST | `/api/batches?batchId=...&sourceRoot=...` | 创建并绑定清单的批次 |
| GET  | `/api/batches` / `/api/batches/{id}` | 批次列表（含 OPEN 状态）/ 详情 |
| POST | `/api/batches/{id}/complete` | 完成批次 |
| POST | `/api/batches/{id}/events` | 上报覆盖事件（JSON body） |
| GET  | `/api/batches/{id}/report` | 单批次报告 |
| GET  | `/api/reports/merged?manifestVersion=...` 或 `batchIds=a&batchIds=b` | 合并报告 |
| POST | `/api/baselines/{name}?batchIds=...` | 保存基线（可选批次，省略取最新版本） |
| GET  | `/api/baselines` / `/api/baselines/{name}` | 基线列表 / 详情 |
| GET  | `/api/baselines/{name}/compare?batchIds=...` | 对比（省略批次取同版本全部批次） |
| POST | `/api/imports` | 导入历史覆盖文件（multipart） |

事件请求体：`{"elementIds": ["STMT:...", "BR:..."], "timestamp": 123}`

## 持久化与并发

- 数据默认写入 `./data/coverage`（清单、批次、命中、基线、版本指针均为 JSON，
  临时文件 + 原子 move 写入）。重启后清单、历史批次、命中、基线全部保留。
- 服务内所有读写通过一把全局读写锁串行化写操作，事件上报与报告查询并发时
  报告始终基于一致的命中快照。

## 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `coverage.data-dir` | `data/coverage` | 持久化目录 |
| `coverage.regression-threshold-percent` | `1.0` | 判定覆盖率回退的阈值（百分点） |

## 构建与测试

```bash
mvn test
```

测试覆盖：乱序/重复/迟到事件、多线程并发上报与并发查询、增量重建与重启复用、
基线四种对比结论、导入的格式/版本/元素不匹配/截断失败与整体拒绝、重启恢复，
以及完整 HTTP 端到端链路。
