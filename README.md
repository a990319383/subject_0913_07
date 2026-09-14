# EVOPS 基础工作区

这是 `001-evops` 题包的初始 Java 工作区。它只提供构建、配置、统一返回、异常处理、MyBatis-Plus 和 H2 本地数据库基础设施，不预先实现 F1 的站点、充电枪、会话和结算业务；F1 由执行模型从这里开始完成。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

题包根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

题面和质检卷在上一级 `packets/` 目录；模型工作区不得复制 `answers.md`、验收测试或参考修正。

## 航天器热真空试验与遥测判读闭环

热真空业务接口统一在 `/api/tvac/**` 下，沿用统一 `ApiResponse` 返回；沿用骨架 Shiro
配置（HTTP Basic，预置账号 `bootstrap/bootstrap`）。

数据模型（见 `schema.sql`，版本标记 `tvac-1`）：

| 表 | 实体 | 业务唯一键 |
| --- | --- | --- |
| `t_tvac_article` 试验件 | `TvacArticle` | `article_code`；`(batch_no, article_name)` |
| `t_tvac_plan` 试验计划 | `TvacPlan` | `plan_code` |
| `t_tvac_channel` 遥测通道 | `TvacChannel` | `channel_code` |
| `t_tvac_curve_point` 温压曲线点 | `TvacCurvePoint` | 按计划+偏移秒查询 |
| `t_tvac_tm_frame` 遥测帧 | `TvacTmFrame` | `frame_seq` |
| `t_tvac_report` 判读报告 | `TvacReport` | `report_no`；`plan_id`（一计划一报告） |

状态流转：

- 试验件：`REGISTERED -> IN_TEST -> COMPLETED`，任意活跃状态可 `SCRAPPED`；
  计划进入/完成试验时联动试验件状态。
- 试验计划：`DRAFT -> ISSUED -> RUNNING -> COMPLETED`，各阶段均可 `TERMINATED`；
  仅 `DRAFT` 可编辑，只有 `RUNNING` 可录入曲线点与遥测帧。
- 遥测通道：`ENABLED <-> DISABLED`，停用通道拒收新帧；录帧时按通道上下限自动
  打 `NORMAL/HIGH/LOW` 越限标记。
- 判读报告：计划完成后生成（`PENDING`）→ 提交判读结论（自动汇总实际循环次数、
  总帧数、异常帧数、温压极值）→ 验收 → 落账。

删除保护：已验收/已落账的判读报告不能删除；非草稿或已有曲线/帧/报告的计划不能删除；
已录入遥测帧的通道只能停用不能删除；已关联计划的试验件不能删除。

主要接口：

- `POST/GET /api/tvac/articles`、`POST /api/tvac/articles/batch`、
  `GET /api/tvac/articles/batches`、`PUT /api/tvac/articles/{id}/status`、
  `DELETE /api/tvac/articles/{id}`
- `POST/GET /api/tvac/plans`、`GET /api/tvac/plans/{id}/detail`、
  `PUT /api/tvac/plans/{id}/status`、`DELETE /api/tvac/plans/{id}`
- `POST/GET /api/tvac/channels`、`PUT /api/tvac/channels/{id}/status`、
  `DELETE /api/tvac/channels/{id}`
- `POST /api/tvac/curve-points`（含 `/batch`）、`GET /api/tvac/curve-points?planId=`
- `POST /api/tvac/frames`（含 `/batch`）、`GET /api/tvac/frames?planId=&limitFlag=`
- `POST/GET /api/tvac/reports`、`GET /api/tvac/reports/{id}/detail`、
  `PUT /api/tvac/reports/{id}/judge|accept|post`、`DELETE /api/tvac/reports/{id}`

测试：`mvn test` 运行 `TvacWorkflowIntegrationTest`（事务自动回滚，不污染文件库）。

## 多租户运营检索与遥测分区聚合（tvac-2）

数据权限模型（`t_tvac_tenant` / `t_tvac_user` / `t_tvac_user_grant`）：

- `bootstrap`（system 角色）：平台账号，跨租户可见，可开通租户/账号；
- `TENANT_ADMIN`：租户管理员，可见本租户全部试验件（含计划/曲线/帧/报告）；
- `TENANT_VIEWER`：租户普通账号，只能看到 `t_tvac_user_grant` 显式授权的试验件；
  即使被写入跨租户授权行，租户条件仍强制生效。
- HTTP Basic 为无状态认证（Shiro `sessionStorageEnabled=false`），不创建容器会话。
- 租户账号新建试验件自动归属本租户；系统账号可用请求体 `tenantId` 指定归属。

运营检索 `POST /api/tvac/operation/search`（请求体 JSON）：

- `objectType`：`ARTICLE`（默认，主表 t_tvac_article）或 `PLAN`（主表 t_tvac_plan）；
- 条件全部 AND 叠加：试验件编号/名称(模糊)/型号/批次/状态、计划编号/名称/状态、
  建档日期区间 `createTimeFrom/To`、计划日期区间 `planTimeFrom/To`、
  温压曲线区间 `tempFromC/To`、`pressureFromPa/To` + `curveCycleNo`
  （同一曲线点同时落入温/压区间，属满足全部计划条件的计划）；
- 分页：`pageNum/pageSize`（pageSize 强制 1–100）或 `cursor`（keyset 游标，
  翻页须携带相同过滤条件）；排序固定 `create_time DESC, id DESC`，
  主键补齐保证时间并列时确定性、可重复；
- 返回 `records / total / pageNum / pageSize / hasNext / nextCursor`；
  `total` 为去重后的主记录数。
- 防放大：计划、曲线、通道等一对多关联全部以 `EXISTS` 半连接或标量子查询下推，
  主记录在计数与分页中均不重复；每条 SQL 都内联租户+角色数据权限条件。

遥测分区聚合 `GET /api/tvac/operation/tm-stats`：

- 直接在 `t_tvac_tm_frame` 上按 `article_id` 分区 GROUP BY，返回每试验件的
  计划数/通道数/最大循环号/帧数/异常帧数/工程值极值；
- `measureType/channelId` 等通道维度过滤走 `EXISTS`/帧表自身列，帧数不被通道关联放大；
- 支持 `cycleFrom/cycleTo`、`limitFlag` 与租户数据范围，pageSize 同样 1–100。
- 已在 300,000 帧 / 1,000 循环与 100,000 试验件+事件数据上完成压测
  （`OperationStressTest`，独立内存库）。

租户开通接口（仅 bootstrap 可写租户/账号）：
`POST /api/tvac/tenancy/tenants`、`POST /api/tvac/tenancy/users`、
`POST /api/tvac/tenancy/grants`、`GET /api/tvac/tenancy/users/{userId}/grants`。

## 观测数据 CSV 批量导入（tvac-3）

表头固定 8 列（首列 `记录类型` 决定行语义）：

```
记录类型,对象编号,观测时间,温压曲线,遥测帧,循环次数,判读结论,来源设备
```

- `CURVE`：温压曲线列 `计划编号;偏移秒;温度[C/F/K];压力[Pa/kPa/MPa]`，单位自动归一化到 ℃/Pa；
- `FRAME`：遥测帧列 `计划编号|通道编号|帧号|原始报文|工程值`，帧号为业务键，按通道限界自动打标；
- `REPORT`：对象编号=计划编号，判读结论列为 `QUALIFIED/UNQUALIFIED/CONDITIONAL`，
  自动汇总循环次数/帧数/异常帧/温压极值后落判读报告。

批量保证（`ObservationImportService`）：

- 文件 SHA-256 校验和唯一：重传同一文件直接返回原批次（`idempotentHit=true`），不重复落库；
  归一化台账 `t_tvac_observation` 以 `(record_type, 业务键)` 为第二道幂等防线；
- 默认 5000 行/片（50,000 行切 10 片），每片独立事务（`REQUIRES_NEW`）、独立 CRC32 校验和，
  分片报文落库；系统失败分片保持 `FAILED`，可对原报文重试，业务键幂等保证重试不重复落数据；
- 逐行隔离：合法/重复/缺列/坏数值混合时单行失败只写错误明细，不回滚整批；
  逐行返回 `SUCCESS`（新增）/`UPDATED`（覆盖）/`FAILED`，错误明细保留原始行号、字段、原值、原因；
- 已验收/已落账的判读报告对应曲线/帧/结论一律拒绝覆盖（`ObservationLockGuard`）。

接口（HTTP Basic，bootstrap）：
`POST /api/tvac/observations/import`（multipart 文件，可选 `shardSize`）、
`POST /api/tvac/observations/import-text`、
`GET /api/tvac/observations/batches/{batchId}`、
`POST /api/tvac/observations/batches/{batchId}/shards/{shardNo}/retry`。

地面站二进制解码结果分片重组（`FrameReassemblyService`）：

- 一个带帧号的逻辑帧按通道拆片，`receive_seq` 记录到达顺序，按 `(会话,通道,片序号)` 去重，
  乱序到达后按通道/片序确定性重组（`POST /api/tvac/frame-sessions`、`.../pieces`、`.../{key}/assemble`）；
- 每片到片复算 CRC32 与客户端校验和比对；重复片同报文幂等丢弃，坏片可用正确报文重发修复；
- 单通道坏帧（CRC 不符/通道停用/归属错误）标记 `BAD` 隔离，不影响其他通道片落帧。

测试：`ObservationImportIntegrationTest`（独立 imptest 内存库），含 50,000 行混合分片、
文件/业务键幂等、失败分片重试、验收/落账保护、乱序帧与单通道坏帧隔离。
