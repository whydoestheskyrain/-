# C2C 游戏账号担保交易平台
这是项目展示地址：http://121.41.63.99:8080/

一个面向**游戏账号等虚拟商品**的 C2C 交易平台，参照电商担保交易模式：买家付款后资金由平台托管，卖家发货（账号凭据支付后才可见），买家确认收货后资金才结算给卖家。覆盖商品发布、下单、双通道支付、发货、收货结算、超时取消、充值、消息通知、AI 客服和运营后台的完整链路。

- **后端**：Spring Boot 2.7 + MyBatis + MySQL 8 + Redis 7（`backend/`）
- **前端**：Vue 3 + Element Plus 电竞深色主题 SPA（`frontend/`）
- **部署**：Docker Compose 四容器编排（MySQL + Redis + 后端 + Nginx）（`deploy/`）

---

## 一、技术栈

### 后端

| 分类 | 选型 | 版本 |
|------|------|------|
| 语言/JDK | Java | 8 |
| 框架 | Spring Boot | 2.7.18 |
| ORM | mybatis-spring-boot-starter（注解 SQL + XML 混用） | 2.3.2 |
| 数据库 | MySQL | 8.0（utf8mb4） |
| 缓存 | spring-boot-starter-data-redis（Redis 7） | 随 Boot 版本 |
| 鉴权 | jjwt（JWT）+ HandlerInterceptor 自研鉴权拦截器 | 0.11.5 |
| 密码加密 | spring-security-crypto（BCrypt） | 5.7.11 |
| 支付 | 支付宝沙箱 alipay-sdk-java（RSA2 验签） | 4.22.113.ALL |
| AI | dashscope-sdk-java（通义千问应用） | 2.14.0 |
| 时间序列化 | jackson-datatype-jsr310（LocalDateTime） | 随 Boot 版本 |
| 测试 | JUnit 5 + Mockito + Spring Boot Test | 随 Boot 版本 |
| 工具 | Lombok、Maven Wrapper | 1.18.30 |

### 前端

| 分类 | 选型 | 版本 |
|------|------|------|
| 框架 | Vue（Composition API + `<script setup>`） | ^3.2.13 |
| UI 库 | Element Plus（深色模式 css-vars） | ^2.13.7 |
| 路由 | vue-router，hash 模式 | ^4.6.4 |
| HTTP | axios（请求/响应拦截器统一封装） | ^1.15.2 |
| 图表 | ECharts（管理后台统计） | ^6.1.0 |

### 部署

- Docker Compose 编排：`mysql:8.0` + `redis:7-alpine` + `eclipse-temurin:8-jre`（后端）+ `nginx:1.25-alpine`
- Nginx 托管前端静态资源（Vue hash 路由回退）并反向代理后端 13 类接口前缀
- 数据持久化到宿主机 `deploy/data/`（MySQL、Redis、上传图片），容器重建不丢数据
- 生产环境全部敏感配置通过 `.env` 环境变量注入，代码仓库不含任何真实密钥

---

## 二、担保交易业务流程

```
买家下单                买家支付                 卖家发货              买家确认收货
  │                       │                       │                      │
  ├─ 校验进行中订单≤3     ├─ 余额支付：             ├─ 订单须为已付款      ├─ FOR UPDATE 锁订单
  ├─ FOR UPDATE 锁买家    │   条件更新扣余额        ├─ 写入账号凭据        ├─ 卖家冻结余额
  ├─ 原子占用商品         │   (balance>=amount)    │   (支付后买家才可见)  │   结算为可用余额
  │  SET status=3         ├─ 支付宝支付：           ├─ 状态→已发货         ├─ 写结算流水
  │  WHERE status=1       │   创建订单→沙箱支付     └─ 状态：2→3           └─ 状态：3→4
  ├─ 卖家/价格以DB为准    │
  └─ 状态=1 待付款        ├─ 卖家冻结余额 += 金额
                          ├─ 写交易流水(变动前后余额)
                          └─ 状态：1→2
        │
        └─ 15 分钟未支付：定时任务（每 5 分钟扫描）→ FOR UPDATE 重新确认状态 → 取消并释放商品（1→取消）
```

订单状态机：`1 待付款 → 2 已付款(待发货) → 3 已发货(待收货) → 4 已完成`，任意时刻可走向 `5 已取消`。
商品状态：`1 在售`、`3 交易中（下单即原子锁定）`；订单取消后商品恢复为 `1`。

---

## 三、功能模块

### 后端（13 个 Controller，87 个 Java 文件，三层架构）

| 模块 | 入口 Controller | 核心能力 |
|------|----------------|----------|
| 用户 | UserController | 注册/登录（BCrypt，历史明文登录自动升级）、JWT 签发、资料编辑、余额与冻结余额、本人账单流水（三级 DTO：公开/本人/管理员） |
| 商品 | ProductController | 发布、编辑、上下架、分页搜索、详情；列表 Redis 缓存 10 分钟，增删改主动失效 |
| 订单 | OrderController | 下单、支付（余额/支付宝）、发货、确认收货、取消、超时取消、买卖家双向订单列表 |
| 充值 | RechargeController + AlipayController | 创建充值单、支付宝沙箱支付、同步跳转、异步通知（订单支付与充值两套独立回调） |
| 消息 | NotificationController | 订单通知/系统公告/反馈回复，按类型聚合为会话，`notification_read` 回执表实现按人独立已读 |
| 反馈 | FeedbackController | 用户提交反馈、管理员回复并通知 |
| 游戏类型 | GameTypeController | 游戏分类字典维护 |
| AI 桌宠 | PetController | 桌宠与皮肤管理（pet_skin/user_pet 表）、AI 对话 SSE 流式接口 |
| 文件 | UploadController | 商品图片本地上传，相对路径入库 |
| 管理后台 | AdminController（`@RequireAdmin`） | 用户/商品/订单管理、资金取证（用户余额+流水）、平台统计概览、系统广播 |
| WebSocket | WsController | WebSocket 预留通道 |

**AI 客服工程能力**：DashScope 应用多轮记忆（sessionId）、SSE 逐字流式输出、意图识别后调用后端真实接口注入上下文（余额、订单统计、最新订单状态）、单用户 15 次/分钟限流、19 个敏感词拒答、问答日志落库、API 失败自动降级 Mock 关键词回复；系统提示词内置平台真实规则与 few-shot 示范，防止模型编造功能。

### 前端（27 个页面）

- **游客/认证**：登录、注册
- **买家/卖家**：首页、商品列表/详情/发布/编辑、订单列表/详情、充值、个人中心（余额/冻结/账单/资料）、消息中心
- **管理后台**：数据概览（ECharts）、用户管理、商品管理、订单管理、游戏类型管理、反馈处理、桌宠皮肤管理
- **全局组件**：AiPet 像素风桌宠聊天窗（深底粉霓虹、遮罩防误点、最小化/关闭）
- 电竞深色主题：页面底 `#0f1117`、卡片 `#1a1d27`、霓虹紫主渐变 + 青色点缀，全站 CSS 变量统一设计令牌，支持"减少界面动效"偏好与 1200/992/768/480 响应式断点

---

## 四、数据库设计（12 张表，MySQL 8 / utf8mb4）

| 表 | 说明 |
|----|------|
| `user` | 用户、角色、余额 balance、冻结余额 frozen_balance、头像等 |
| `product` | 商品：卖家、游戏类型、价格、账号凭据（支付后才对买家可见）、状态、审核拒绝原因 |
| `order` | 订单：买卖家、价格、状态、支付方式、支付时间、支付宝流水号、账号凭据 |
| `recharge_order` | 充值单：订单号、金额、状态、支付宝流水号（回调幂等载体） |
| `transaction_log` | 资金流水：类型、变动金额、变动前后余额、关联订单，对账审计依据（BigDecimal） |
| `transaction` | 管理后台交易记录 CRUD（早期模块，金额为 Double，与 transaction_log 并存） |
| `notification` | 站内通知（订单消息/系统广播/反馈回复） |
| `notification_read` | 广播按人已读回执（用户+消息联合主键） |
| `feedback` | 用户反馈与管理员回复 |
| `game_type` | 游戏分类字典 |
| `pet_skin` / `user_pet` | 桌宠皮肤配置与用户当前穿戴 |

建表脚本：
- 开发参考：`backend/init.sql`
- **生产部署权威脚本**：`deploy/mysql/init/init.sql`（MySQL 容器首次启动自动执行）

---

## 五、核心技术设计（面试重点）

### 1. 并发防超卖——原子条件更新

```xml
<!-- ProductMapper.xml -->
<update id="reserveIfAvailable">
    UPDATE product SET status = 3, update_time = NOW()
    WHERE id = #{id} AND status = 1
</update>
```

50 个买家同时下单时，InnoDB 行锁保证只有一个事务的 UPDATE 匹配到 `status=1`（影响行数=1），其余返回 0 被业务拒绝。下单全程在 `@Transactional` 中：锁买家 → 进行中订单数校验（≤3）→ 占用商品 → 以数据库中的卖家/价格建单（不信任客户端传值）。

### 2. 支付回调幂等——状态原子标记

```java
// RechargeOrderMapper
@Update("UPDATE recharge_order SET status = 1, trade_no = #{tradeNo}, pay_time = NOW(), update_time = NOW() " +
        "WHERE order_no = #{orderNo} AND status = 0")
int markSuccessIfPending(@Param("orderNo") String orderNo, @Param("tradeNo") String tradeNo);
```

支付宝重复/并发异步通知时，只有第一次 UPDATE 成功（0→1）并执行入账与流水，后续更新 0 行直接幂等返回。订单支付回调另用 `SELECT ... FOR UPDATE` 锁住订单行并前置检查 `status >= 已付款`。

### 3. 资金一致性——条件更新 + 全程流水

```xml
<update id="deductBalance">
    UPDATE user SET balance = balance - #{amount}
    WHERE id = #{userId} AND balance >= #{amount}
</update>
```

扣余额/冻结/结算全部带余额条件，核心路径使用 `BigDecimal`；每次资金变动写 `transaction_log`，记录变动前后余额。余额支付的"扣款 → 卖家冻结 → 订单状态 → 流水"在同一事务内，异常整体回滚。

### 4. 状态竞争——行锁 + 前置状态校验

超时定时任务（`@Scheduled` 每 5 分钟扫描超 15 分钟未付款订单）调用的取消逻辑会重新 `FOR UPDATE` 锁定订单并确认其仍为待付款；与支付回调并发时，先拿到锁的一方完成状态流转，另一方读到已付款/已取消即放弃，杜绝已付款订单被错误释放商品。

### 5. 鉴权与安全边界

- 登录签发 JWT，token 中**不含角色**；`AuthInterceptor` 每个请求实时查库校验用户存在性与状态，权限变更立即生效
- 四步鉴权：OPTIONS 预检放行 → JWT 格式校验 → 用户存在性校验 → `@RequireAdmin` 管理员校验
- 用户身份一律取自 token（`CurrentUser.getUserId(request)`），不接受请求体传入的 userId
- 支付宝回调 RSA2 验签；全部密钥使用 `${ENV:default}` 占位，真实值仅存在于不入库的 `application-local.yml` 或容器环境变量

---

## 六、测试

| 类型 | 数量 | 结果 | 证据 |
|------|------|------|------|
| Mockito 单元测试（订单 7 + 充值幂等 2 + 序列化 2 + 上下文 1） | 12 | 全部通过 | `backend/target/surefire-reports/` |
| 真实 MySQL 并发集成测试 | 2 类 × 3 轮重复 = 6 次 | 全部通过 | `OrderConcurrencyIT` / `RechargeConcurrencyIT` |

并发集成测试要点：
- 使用**独立隔离库** `trade_concurrency_test`（仅复制表结构、0 数据），启动时 `SELECT DATABASE()` 断言防误连开发/生产库
- **不 Mock 任何 Mapper**：CountDownLatch 让 50/20 个线程同时进入，Hikari 连接池 60 保证每线程独立连接与独立事务，完整经过 Spring 事务 → MyBatis → InnoDB
- 结论以数据库最终状态为准：50 并发抢购 3 轮共 150 请求，每轮仅 1 笔订单落库（1 成功 / 49 业务拒绝 / 0 系统异常），无超卖无死锁；20 并发重复充值回调 3 轮共 60 请求，每轮余额仅入账一次、流水仅 1 条

执行方式：

```bash
# 常规单元测试（IT 类不匹配 Surefire 默认规则，不会被执行）
cd backend && mvn test

# 显式执行并发集成测试（需先准备隔离库 trade_concurrency_test）
mvn test -Dtest="OrderConcurrencyIT,RechargeConcurrencyIT"
```

完整核验过程见 `backend/TEST-REPORT-V3.md`。

---

## 七、目录结构

```
c2c-game-trade/
├── backend/                     # Spring Boot 后端
│   ├── src/main/java/com/trade/virtual_trade/
│   │   ├── common/              # Result 统一响应
│   │   ├── config/              # WebConfig、RedisConfig、上传配置
│   │   ├── security/            # JWT 工具、鉴权拦截器、@RequireAdmin、CurrentUser
│   │   ├── exception/           # 全局异常处理
│   │   ├── controller/          # 13 个 Controller
│   │   ├── service/impl/        # 业务逻辑（订单/充值/商品/用户/AI/通知…）
│   │   ├── mapper/              # MyBatis Mapper 接口
│   │   ├── entity/ dto/ vo/     # 实体与数据传输对象
│   │   ├── task/                # 订单超时定时任务
│   │   └── VirtualTradeApplication.java
│   ├── src/main/resources/
│   │   ├── mapper/*.xml         # XML SQL（含行锁、条件更新）
│   │   ├── application.yml
│   │   └── application-local.yml.example
│   ├── src/test/                # 12 单元测试 + 6 并发集成测试
│   ├── init.sql
│   └── pom.xml
├── frontend/                    # Vue 3 前端
│   ├── src/views/               # 27 个页面
│   ├── src/components/AiPet.vue # AI 桌宠
│   ├── src/router/ utils/ assets/css/
│   └── vue.config.js            # devServer 代理 → http://localhost:8081
└── deploy/                      # Docker Compose 部署
    ├── docker-compose.yml
    ├── .env.example             # 环境变量模板（复制为 .env 填写）
    ├── mysql/init/init.sql      # 生产建表脚本（容器首启自动执行）
    ├── mysql/conf/my.cnf        # 强制 utf8mb4，禁止 latin1 协商
    └── nginx/conf.d/trade.conf  # 静态托管 + 后端反代
```

---

## 八、本地快速启动

### 前置要求

- JDK 8、Maven 3.6+（项目自带 Maven Wrapper）
- Node.js 16+、npm
- MySQL 8（创建 `trade_db`，执行 `backend/init.sql`）、Redis 7（默认无密码）

### 1. 后端

```bash
cd backend
# 可选：复制本地私密配置（数据库密码、支付宝沙箱密钥、DashScope Key）
cp src/main/resources/application-local.yml.example application-local.yml
# 按需填写后再启动；不填也能启动（支付与 AI 功能不可用，其余正常）

mvn spring-boot:run
# 服务端口 8081
```

### 2. 前端

```bash
cd frontend
npm install
npm run serve
# devServer 端口 8082，接口代理到 http://localhost:8081
```

### 3. 访问

- 前端：http://localhost:8082
- 后端健康/接口基址：http://localhost:8081

---

## 九、生产部署（Docker Compose）

```bash
cd deploy
cp .env.example .env            # 填写 MySQL 密码、JWT 密钥、支付宝/AI 密钥、公网 IP

# 放置构建产物
#   后端 jar  → deploy/backend/virtual-trade.jar （mvn clean package 产物）
#   前端产物  → deploy/nginx/html/               （npm run build 的 dist 内容）

docker compose up -d            # 首次启动自动建库建表
docker compose logs -f backend  # 查看日志
docker compose restart backend  # 升级 jar 后重启
```

- 对外仅暴露 Nginx 的 **8080** 端口（云安全组放行），MySQL/Redis/后端均不暴露宿主机
- 访问入口：`http://服务器IP:8080`
- Nginx 反代前缀：`/user /product /order /gameType /transaction /recharge /alipay /pet /feedback /notification /admin /api /uploads`，`/ws/` 支持 WebSocket 升级
- MySQL 挂载 `my.cnf` 强制 `skip-character-set-client-handshake`，根治客户端 latin1 协商导致中文变 `????`

---

## 十、已知不足与优化方向

- 管理后台部分统计接口在应用层循环聚合，数据量增大后应下沉为 SQL `GROUP BY` + 分页
- 历史遗留：`transaction`（Double 金额）与核心资金流水 `transaction_log`（BigDecimal）并存，计划合并统一
- 外围辅助服务存在少量 Double 金额入参，核心交易路径已全部 BigDecimal
- 尚未引入消息队列；当前异步任务由 Spring `@Scheduled` 承担
- 并发集成测试已覆盖「抢购超卖」「充值回调幂等」，支付回调与超时取消竞争、余额并发支付的端到端场景可继续补充

---

## 十一、统一约定

- 接口响应：`{"code": 200, "msg": "success", "data": {...}}`
- 写操作遵循 Controller → Service → Mapper 三层，事务方法使用 `@Transactional`
- 金额一律 BigDecimal 计算与传输，前端使用千位分隔符格式化
- 商品图片存相对路径，由 Nginx 代理，避免硬编码 localhost 导致部署后裂图
- 前端 hash 路由；后端重定向使用 `HttpServletResponse.sendRedirect()`（`@RestController` 返回 `redirect:` 字符串不会触发 302）
