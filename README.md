# 园区月台预约调度系统（dockyard）

覆盖 **预约 → 进场 → 排队叫号 → 靠台卸货 → 出场核费 → 异议处理** 全流程的浏览器系统，
解决「纸排队、仓库不知道装什么、滞留费三方扯皮」的问题：每一步都有操作人、时间和状态，
等待时长与费用按统一规则自动核算，原始金额与费率快照永久保留。

- 技术栈：Java 21 · Spring Boot 3.3 · Spring Security · Spring Data JPA · PostgreSQL 16 · Flyway · Thymeleaf（少量原生 JavaScript，无 React/Vue）
- 金额：全程 `BigDecimal`，数据库 `numeric(12,2)`
- 业务时间：存储 `timestamptz`，所有业务口径一律按 **Asia/Shanghai**
- 历史记录：只追加 / 状态流转，**没有任何物理删除路径**（见 `V1__init_schema.sql` 的部分唯一索引与服务层约定）

---

## 一、生产部署（Docker Compose）

前置：已安装 Docker 与 Docker Compose。

```bash
cp .env.example .env          # 编辑其中的数据库口令与首个管理员账号/密码（密码至少 8 位）
docker compose up --build -d
```

- 应用：http://127.0.0.1:8080 （仅绑定宿主机回环地址；对互联网暴露请自行前置 **TLS 反向代理**）
- PostgreSQL 端口**不发布**到宿主机，仅 compose 内网可达。
- 首次启动 Flyway 自动建表，并按 `.env` 中的 `ADMIN_USERNAME/ADMIN_PASSWORD` 创建**首个调度员管理员**（密码 BCrypt 加密入库，登录后请尽快改密；账号建好后可把这两项从 `.env` 删除再重启）。
- 生产默认**不创建任何演示账号、不播种样例数据**；`.env` 缺失或未设置必填变量时 compose 直接拒绝启动。
- 容器以非 root（uid 1001）运行：只读根文件系统、`cap_drop: ALL`、`no-new-privileges`、内存/PID 上限。

停止：

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 连数据一起清空
```

## 二、本地开发启动（含演示数据）

前置：JDK 21、Maven 3.9+、一个可连通的 PostgreSQL 14+。

使用 **`dev` profile** 才会播种演示账号与 20 条样例预约：

```bash
createdb dockyard            # 或在已有实例里建库
DB_URL=jdbc:postgresql://localhost:5432/dockyard \
DB_USER=dockyard DB_PASSWORD=dockyard \
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

dev profile 的本地库默认连接参数（用户/密码 `dockyard`）仅用于本机，请勿在生产启用该 profile。

## 三、运行测试

测试使用 **Zonky 内嵌 PostgreSQL**（自动启用 dev profile 播种），不需要本机安装 Docker 或 PG（首次运行会自动下载一个 PG 二进制，需要联网）。

```bash
mvn verify
```

覆盖的关键场景（共 13 个用例）：

| 测试类 | 覆盖点 |
| --- | --- |
| `OverrideBookingTest` | 30 分钟槽容量拦截第 7 辆；无原因不许插单；写明原因插单成功并留痕 |
| `LateArrivalTest` | 早到进等候区；迟到 45 分钟边界（45 不标、46 标红）；同一预约码重复进场拒绝 |
| `ConcurrentDockTest` | 两个线程同时把两辆车派到同一月台，恰好一成一败，库里无双占 |
| `CrossRateDateFeeTest` | 计费等待跨上海自然日按当日费率分段（120 元/时 + 240 元/时）；核费后改价不影响快照；90 分钟内 0 元 |
| `DisputeHandlingTest` | 驳回维持原价；成立只改最终金额、原始金额保留；跨承运商提异议被拒；已处理不可覆盖 |
| `GoldenPathWalkthroughTest` | 完整 HTTP 走查：预约两车→放行→叫号→卸货→出场核费→异议，页面合计与 CSV 逐格一致 |
| `WebAuthorizationTest` | 跨角色直连 URL 返回 403（权限不靠藏按钮） |
| `PageSmokeTest` | 四个角色的全部页面真实渲染，捕获模板错误 |

## 四、初始化账号（仅 dev profile）

> ⚠️ 以下账号与统一初始密码 **`dock1234`** 只在 `dev` profile（`app.seed.demo-accounts=true`）下创建，生产部署不会出现，登录页也不再展示账号清单。

| 用户名 | 角色 | 说明 |
| --- | --- | --- |
| `carrier1` | 承运商 | 顺达物流（标准货为主） |
| `carrier2` | 承运商 | 冷鲜运输（冷藏货） |
| `guard` | 门卫 | 凭预约码放行、标记早到/迟到 |
| `warehouse` | 仓库 | 靠台 / 开始卸货 / 完成 / 出场（自动核费） |
| `dispatcher` | 调度员 | 叫号派台、超额插单、费率维护、异议处理 |

月台：D1–D4 普通、D5 冷藏、D6 大件。
费率样例：顺达今日 60 元/时、次日 90 元/时；冷鲜今日 80 元/时、次日 100 元/时。

### 安全机制

- **双层鉴权**：URL 角色规则 + 服务方法 `@PreAuthorize`；承运商的数据归属（本司预约/核费单）在控制器/服务层再卡，越权一律返回 **403**（不是 200 错误页），不存在的资源返回 400/404。
- **登录防护**：同一用户名连续失败 5 次锁定 15 分钟（单机内存计数，重启清空）；登录成功计数清零。
- **CSV 导出防公式注入**：以 `= + - @ Tab/CR` 开头的单元格自动前置单引号，Excel/WPS 打开核费明细不会执行其中公式。
- **入参校验**：全部表单做服务端长度/格式校验（Bean Validation），非法输入返回 400 友好页，不暴露堆栈。
- **响应头**：CSP（脚本只允许同源外置 JS）、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、HSTS（经 HTTPS 生效）。
- CSRF 默认开启；SQL 全部参数化；Thymeleaf 默认 HTML 转义。

## 五、浏览器走查

见 **[`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md)**，按
「预约两辆车 → 门卫放行 → 调度叫号 → 仓库卸货 → 出场核费 → 承运商提异议」逐项核对，
每一步都写了预期页面数字与 CSV 对账方法。系统自带的样例出场单（车牌 `沪A00023`，60.00 元）可用于免操作快速对账。

## 六、业务规则口径

- **容量**：按上海墙钟切 30 分钟槽，每槽全园区 6 个名额（`app.slot.capacity` 可配）；
  并发提交由 PostgreSQL 事务咨询锁（`pg_advisory_xact_lock`，以槽起点为 key）串行化容量检查，不会超额；
  插单不受容量限制，但原因必填，原因与操作人写入 `operation_event`。
- **进场**：只能凭有效预约码进场一次（按预约码行锁串行化并发扫码）；早于时段起点标记「早到等候」；
  晚于时段终点 **45 分钟** 标记迟到（边界 45 分钟不算）。同车在场、同码重复使用都拒绝。
  取消与进场在同一预约行锁上互斥，不会出现“已进场又被取消”。
- **派台**：队列按实际进场先后；月台类型必须匹配；并发派台由
  `SELECT … FOR UPDATE` 串行化 + 数据库部分唯一索引 `uq_dock_occupancy` 双重保证「一个月台同时只有一辆车」。
- **等待时长**：`靠台时刻 − 进场时刻`（靠台时固化到预约单）。
- **核费**：免费 90 分钟；超出部分 = 起算点（进场+90 分）到靠台点；
  跨自然日按天分段，各段用承运商**当日费率**，费率与每段金额写进 `fee_segment` 快照；
  `fee_settlement.original_amount` 一旦生成永不更新；核费按预约行锁串行化，重复/并发触发只生成一张单（幂等）。
  **计费当日若未配置费率，出场会被整体拦下**（车辆停在“卸货完成”、不产生核费单），需调度在「费率维护」补配后重新出场；免费时长内的 0 元单不要求费率。
- **费率维护**：调度员在 `/rates` 按承运商 + 上海自然日配置费率；保存走数据库原子 upsert（同日并发保存不产生重复行）。改价只影响之后核费的单。
- **异议**：承运商发起（只能对本司单）；调度驳回=维持原价；成立=只写 `final_amount`，
  原价、费率快照、分段明细全部保留；异议只追加、已处理不可覆盖；
  同一单的待处理异议由部分唯一索引 `uq_dispute_open_per_appt` 保证唯一，双击/并发提交不会产生两条 OPEN。
- **业务时钟**：所有业务时间戳（预约/事件/核费/异议/费率）统一取自 `YardClock`（Asia/Shanghai，测试可固定），
  实体不再各自取系统时钟；数据库列的 `default now()` 仅作最后防线。
- **审计留痕**：每个状态动作记专属事件类型——提交/插单/取消（`CANCELLED`，不再误记成提交）、进场/早到/迟到、
  叫号/靠台/卸货/完成/出场、核费，以及提异议（`DISPUTE_RAISED`）、驳回（`DISPUTE_REJECTED`）、
  成立调整（`DISPUTE_ADJUSTED`），事件在详情时间线可查。`operation_event.actor_id/dock_id`、
  预约提交人/插单人、核费生成人、异议提起人/处理人、费率配置人均有外键指向真实用户/月台（V3），杜绝悬空审计身份。
- **预约码**：全站由 `AppointmentCodeGenerator` 单一实现（运行时与播种共用），格式
  `YY` + 槽位上海日 `yyMMdd` + 数据库序列（≥6 位、不截断），并发/多实例不重号。
- **槽位规则可配**：`app.slot.length-minutes`（默认 30）同时驱动容量切槽、播种数据与表单下拉选项，
  改配置不会出现下拉与实际槽位不一致；取值需能整除 1440。
- **防重**：`uq_appt_plate_in_yard`（同车在场唯一）、`uq_dock_occupancy`（同月台占用唯一）、
  `uq_dispute_open_per_appt`（同单待处理异议唯一）、`fee_settlement.appointment_id` 唯一（每单一张核费单）
  是数据库级约束，应用层绕不过去。

## 七、主要代码结构

```
src/main/java/com/example/dockyard/
├── domain/          JPA 实体与枚举（状态机 AppointmentStatus 等）
├── repo/            Spring Data 仓储（含加锁查询、占用/在场检测）
├── security/        Spring Security：URL + @PreAuthorize 双层鉴权
├── service/
│   ├── AppointmentService.java       容量校验 + 插单
│   ├── GateService.java              进场 / 早到 / 迟到
│   ├── QueueAllocationService.java   排队、叫号派台（并发安全）
│   ├── WarehouseOperationService.java 靠台/卸货/完成/出场
│   ├── FeeService.java               90 分钟免费 + 跨日分段 + 费率快照（缺费率硬失败、幂等）
│   ├── RateService.java              承运商当日费率原子 upsert（/rates 页面）
│   ├── DisputeService.java           异议（原金额不可抹）
│   ├── YardClock.java                全站唯一 Asia/Shanghai 业务时钟（测试可固定）
│   └── DashboardService.java         首页聚合
├── web/             控制器（只收参数、调服务、渲染）+ CSV 下载 + 费率维护
└── bootstrap/       DataInitializer：账号 / 月台 / 费率 / 20 条样例
src/main/resources/
├── db/migration/V1__init_schema.sql  Flyway 建表
├── db/migration/V2__dispute_open_guard.sql  待处理异议唯一索引
├── db/migration/V3__audit_actor_foreign_keys.sql  审计操作人/月台外键
├── templates/                        Thymeleaf 页面
└── static/                           一个 CSS + 两处少量原生 JS
```
