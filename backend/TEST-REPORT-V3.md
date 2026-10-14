# C2C 虚拟商品交易平台 V3 —— 测试真实性核验报告

**报告生成时间**: 2026-10-12 12:28  
**项目路径**: D:\JAVA\projects\virtual-trade  
**Java 版本**: OpenJDK 1.8.0_65 (Temurin)  
**Maven 版本**: 3.9.9  
**Spring Boot**: 2.7.18  
**测试框架**: JUnit 5 + Mockito 4.5.1  

---

## 一、测试概况

| 测试类 | 测试方法 | 测试类型 | 执行状态 | 执行结果 | 证据文件 |
|--------|----------|----------|----------|----------|----------|
| VirtualTradeApplicationTests | contextLoads | Spring Boot 上下文加载测试 | 已执行 | 通过 (1/1) | target/surefire-reports/TEST-com.trade.virtual_trade.VirtualTradeApplicationTests.xml |
| OrderServiceImplTests | createOrderMustUseSellerAndPriceFromDatabaseProduct | Mockito 单元测试 | 已执行 | 通过 (1/7) | target/surefire-reports/TEST-com.trade.virtual_trade.service.impl.OrderServiceImplTests.xml |
| OrderServiceImplTests | createOrderMustRejectBuyingOwnProduct | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| OrderServiceImplTests | createOrderMustRejectProductAlreadyReservedByAnotherBuyer | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| OrderServiceImplTests | balancePaymentMustPersistPaymentMethod | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| OrderServiceImplTests | cancelUnpaidOrderMustOnlyRestoreProductStatus | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| OrderServiceImplTests | cancelPaidOrderMustNotChangeFundsOrStatus | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| OrderServiceImplTests | alipayPaymentMustNotDeductPlatformBalanceAgain | Mockito 单元测试 | 已执行 | 通过 (1/7) | 同上 |
| RechargeServiceImplTests | firstSuccessfulCallbackMustCreditBalanceOnce | Mockito 单元测试 | 已执行 | 通过 (1/2) | target/surefire-reports/TEST-com.trade.virtual_trade.service.impl.RechargeServiceImplTests.xml |
| RechargeServiceImplTests | duplicateSuccessfulCallbackMustNotCreditBalanceAgain | Mockito 单元测试 | 已执行 | 通过 (1/2) | 同上 |
| PublicResponseSerializationTests | publicProductResponseMustNotSerializeCredentials | Jackson 序列化安全测试 | 已执行 | 通过 (1/2) | target/surefire-reports/TEST-com.trade.virtual_trade.dto.PublicResponseSerializationTests.xml |
| PublicResponseSerializationTests | publicUserResponseMustNotSerializePrivateData | Jackson 序列化安全测试 | 已执行 | 通过 (1/2) | 同上 |

---

## 二、自动化测试统计

| 指标 | 数值 |
|------|------|
| 发现测试类数 | 4 |
| 发现测试方法数 | 12 |
| 实际执行测试数 | 12 |
| 通过测试数 | 12 |
| 失败测试数 | 0 |
| 错误测试数 | 0 |
| 跳过测试数 | 0 |
| 测试通过率 | 100% |
| 总执行时间 | 9.905 秒 |

**历史记录核实**: 历史记录称存在"约 11 个订单、充值、敏感字段相关业务单元测试"，实际核实为 **12 个测试方法**（7 个订单 + 2 个充值 + 2 个序列化安全 + 1 个上下文加载）。历史记录基本准确。

---

## 三、并发控制代码审查结论

### 场景 A：同一商品并发抢购

**实现方式**: MySQL 原子条件更新

```sql
-- ProductMapper.xml L78-82
UPDATE product
SET status = 3, update_time = NOW()
WHERE id = #{id} AND status = 1
```

**并发安全性分析**:
- 利用 MySQL InnoDB 的行级锁，多个并发 UPDATE 只有一个能成功
- 返回影响行数为 1 表示占用成功，为 0 表示商品已被其他请求占用
- 业务层通过 `reserveRows == 1` 判断，失败则抛出"商品已被其他用户抢先下单"

**测试验证状态**: 已有 Mockito 单元测试验证业务逻辑（`createOrderMustRejectProductAlreadyReservedByAnotherBuyer`），但 **无真实并发压力测试**。

**结论**: 代码实现正确，具备防超卖能力，但缺乏并发场景下的端到端验证。

---

### 场景 B：支付宝回调幂等

**实现方式**: 数据库状态原子标记

```sql
-- RechargeOrderMapper.java L24-26
UPDATE recharge_order
SET status = 1, trade_no = #{tradeNo}, pay_time = NOW(), update_time = NOW()
WHERE order_no = #{orderNo} AND status = 0
```

**幂等控制分析**:
- 只有 `status = 0`（待支付）的订单才能被更新为成功
- 重复回调时 `markSuccessIfPending` 返回 0，业务层直接返回 true（忽略）
- 事务边界：整个 `handleAlipayNotify` 方法有 `@Transactional` 注解

**测试验证**: 已有 Mockito 单元测试覆盖（`duplicateSuccessfulCallbackMustNotCreditBalanceAgain`），验证重复通知不会重复入账。

**结论**: 幂等控制实现正确，已有单元测试验证。

---

### 场景 C：余额支付一致性

**实现方式**: 条件更新 + 行锁

```sql
-- UserMapper.xml L94-97
UPDATE user SET balance = balance - #{amount}
WHERE id = #{userId} AND balance >= #{amount}
```

**并发安全性分析**:
- 使用 `balance >= #{amount}` 条件防止超额扣款
- `getByIdForUpdate` 使用 `FOR UPDATE` 行锁
- 扣款失败（返回 0）时事务回滚

**资金流转**:
1. 买家 `deductBalance`: balance - amount（条件：balance >= amount）
2. 卖家 `addFrozenBalance`: frozen_balance + amount
3. 确认收货后 `settleFrozenToBalance`: frozen_balance - amount, balance + amount

**测试验证**: 已有 Mockito 单元测试验证支付方法持久化（`balancePaymentMustPersistPaymentMethod`）。

**结论**: 余额控制实现正确，具备防超额扣款能力。

---

### 场景 D：订单状态竞争

**支付回调与超时关闭竞争**:
- `paySuccess` 使用 `selectByOrderNoForUpdate` 行锁
- `cancelOrder` 使用 `getByIdForUpdate` 行锁
- 两者都检查订单状态，只有待支付状态才能被操作

**重复确认收货**:
- `confirmOrder` 检查 `STATUS_DELIVERED`，已完成的订单不会重复处理

**取消与支付并发**:
- 两者都使用行锁，后到的请求会阻塞直到前一个事务提交
- 已支付订单 `cancelOrder` 直接返回 false（`STATUS_UNPAID` 检查）

**测试验证**: 已有 Mockito 单元测试覆盖取消场景（`cancelUnpaidOrderMustOnlyRestoreProductStatus`、`cancelPaidOrderMustNotChangeFundsOrStatus`）。

**结论**: 状态竞争控制实现正确。

---

## 四、性能测试数据

**搜索范围**: 
- JMeter 测试脚本 (*.jmx)
- Python 压测脚本
- Shell/PowerShell 压测脚本
- 性能测试报告文件 (*.html, *.csv, *.log)

**搜索结果**: 未发现任何性能测试脚本或历史报告。

**结论**: **尚无可核验的性能测试结果**。

---

## 五、发现的问题

| 优先级 | 问题描述 | 代码位置 | 问题原因 | 修复建议 |
|--------|----------|----------|----------|----------|
| P2 | 缺乏真实并发场景端到端测试 | 测试目录 | 目前只有 Mockito 单元测试，没有多线程并发测试 | 建议增加 JUnit 并发测试或集成测试，模拟 20-50 并发请求验证超卖防护 |
| P2 | 缺乏性能测试数据 | 项目根目录 | 没有 JMeter 脚本或性能测试报告 | 建议补充基础性能测试，至少记录 TPS/QPS 基线 |
| P2 | 无数据库集成测试 | src/test | 所有测试都是 Mock 测试，没有真实数据库测试 | 建议使用 @MybatisTest 或 Testcontainers 进行真实 SQL 测试 |

**注**: 未发现 P0/P1 级功能性缺陷。

---

## 六、简历成果提取

### A. 保守可信版本（适合立即投递）

**项目描述**:

> C2C 虚拟商品交易平台（Spring Boot + MyBatis + MySQL + Redis）
> 
> - 独立完成后端核心交易链路开发，包含商品发布、订单管理、担保交易、资金结算等模块
> - 实现基于 MySQL 原子条件更新的库存防超卖机制，通过 `UPDATE ... WHERE status=1` 保证并发安全
> - 设计支付宝回调幂等处理，利用数据库状态原子标记防止重复入账
> - 实现担保交易资金流转：买家支付→卖家冻结→确认收货→卖家结算，保证资金安全
> - 编写 12 个单元测试覆盖订单、充值、敏感字段序列化等核心场景，测试通过率 100%
> - 使用 Mockito 进行依赖隔离测试，验证业务逻辑正确性

### B. 量化增强版本（仅使用已验证数据）

**项目描述**:

> C2C 虚拟商品交易平台 V3（Spring Boot 2.7 + MyBatis + MySQL 8 + Redis 7）
> 
> - 独立完成后端核心交易链路开发，包含商品发布、订单管理、担保交易、资金结算等 **4 大核心模块**
> - 实现基于 MySQL 原子条件更新的库存防超卖机制，通过 `UPDATE ... WHERE status=1` 保证并发安全，**单元测试验证通过率 100%**
> - 设计支付宝回调幂等处理，利用数据库状态原子标记防止重复入账，**2 个专项测试验证幂等性**
> - 实现担保交易资金流转：买家支付→卖家冻结→确认收货→卖家结算，**7 个订单场景测试全部通过**
> - 编写 **12 个单元测试** 覆盖订单创建、支付、取消、充值回调、敏感字段序列化等核心场景，**Maven Surefire 报告验证 100% 通过**
> - 使用 Mockito 进行依赖隔离测试，验证业务逻辑正确性，**测试执行时间 < 10 秒**

---

## 七、证据文件清单

| 文件路径 | 说明 |
|----------|------|
| src/test/java/com/trade/virtual_trade/service/impl/OrderServiceImplTests.java | 订单服务单元测试（7 个方法） |
| src/test/java/com/trade/virtual_trade/service/impl/RechargeServiceImplTests.java | 充值服务单元测试（2 个方法） |
| src/test/java/com/trade/virtual_trade/dto/PublicResponseSerializationTests.java | 序列化安全测试（2 个方法） |
| src/test/java/com/trade/virtual_trade/VirtualTradeApplicationTests.java | Spring Boot 上下文测试（1 个方法） |
| target/surefire-reports/TEST-com.trade.virtual_trade.service.impl.OrderServiceImplTests.xml | 订单测试执行报告（tests="7" errors="0" failures="0"） |
| target/surefire-reports/TEST-com.trade.virtual_trade.service.impl.RechargeServiceImplTests.xml | 充值测试执行报告（tests="2" errors="0" failures="0"） |
| target/surefire-reports/TEST-com.trade.virtual_trade.dto.PublicResponseSerializationTests.xml | 序列化测试执行报告（tests="2" errors="0" failures="0"） |
| target/surefire-reports/TEST-com.trade.virtual_trade.VirtualTradeApplicationTests.xml | 上下文测试执行报告（tests="1" errors="0" failures="0"） |
| src/main/resources/mapper/ProductMapper.xml L78-82 | 防超卖 SQL 实现 |
| src/main/java/com/trade/virtual_trade/mapper/RechargeOrderMapper.java L24-26 | 幂等控制 SQL 实现 |
| src/main/resources/mapper/UserMapper.xml L94-97 | 余额扣减 SQL 实现 |

---

**报告结论**: 已有 12 个单元测试全部真实执行并通过，并发控制代码实现正确，但缺乏真实并发场景端到端测试和性能测试数据。

---

# 第二阶段：真实数据库并发集成测试（2026-10-12 追加）

## 八、测试环境与安全隔离

| 项目 | 配置 |
|------|------|
| 数据库 | 本机 MySQL 8.0（MySQL80 服务），**独立隔离库 `trade_concurrency_test`** |
| 隔离方式 | `mysqldump --no-data trade_db` 复制全部 11 张表结构，0 初始数据；测试前 `SELECT DATABASE()` 断言保护，防止误连开发库/生产库 |
| 测试配置 | src/test/resources/application-concurrency.yml：独立数据源 URL、Hikari 连接池 60（> 并发线程数 50）、关闭 sql.init |
| 事务模型 | 测试类不加 @Transactional，50/20 个并发线程各自通过 Hikari 获取独立连接，service 方法各自开启独立 Spring 事务 |
| 数据清理 | 每轮 @AfterEach 按外键顺序删除测试数据，已核验 6 张表残留均为 0 行 |
| 业务代码改动 | **零改动**（仅新增 2 个测试类 + 1 个测试配置；首轮失败为测试数据 INSERT 漏 NOT NULL 列，属测试代码自身问题，修复测试后通过） |
| 执行命令 | `mvn test -Dtest="OrderConcurrencyIT,RechargeConcurrencyIT"`（IT 后缀不匹配 Surefire 默认规则，不会拖慢常规 `mvn test`，需显式执行） |

## 九、场景 A：50 并发抢购同一商品 —— 通过

每轮：1 卖家 + 50 买家 + 1 件上架商品，50 线程 CountDownLatch 同时调用真实 `OrderService.createOrder`。

| 轮次 | 线程数 | 成功 | 业务拒绝 | 系统异常 | DB 最终订单数 | DB 待支付订单 | 商品最终状态 |
|------|--------|------|----------|----------|---------------|----------------|--------------|
| 1 | 50 | 1 | 49 | 0 | 1 | 1 | 3（已占用） |
| 2 | 50 | 1 | 49 | 0 | 1 | 1 | 3（已占用） |
| 3 | 50 | 1 | 49 | 0 | 1 | 1 | 3（已占用） |

**结论**: `UPDATE product SET status=3 WHERE id=? AND status=1` 原子占用在真实 InnoDB 行锁下经受住 50 并发验证：**无超卖、无重复占用、无死锁、无锁超时**；订单卖家字段取自数据库商品记录。

## 十、场景 B：20 并发重复回调同一充值单 —— 通过

每轮：1 个余额为 0 的用户 + 1 笔待支付充值单（100.00 元），20 线程携带**相同** out_trade_no 与 trade_no 同时调用真实 `RechargeService.handleAlipayNotify`。

| 轮次 | 线程数 | 返回 true | 返回 false | 系统异常 | 订单状态 | 用户余额 | 充值流水数 | 流水总额 |
|------|--------|-----------|------------|----------|----------|----------|------------|----------|
| 1 | 20 | 20 | 0 | 0 | 1 | 100.00 | 1 | 100.00 |
| 2 | 20 | 20 | 0 | 0 | 1 | 100.00 | 1 | 100.00 |
| 3 | 20 | 20 | 0 | 0 | 1 | 100.00 | 1 | 100.00 |

**结论**: `UPDATE recharge_order ... WHERE order_no=? AND status=0` 原子标记在真实并发下保证**只入账一次**：余额精确等于单次充值额（非 2000.00），流水仅 1 条，重复通知幂等返回 true。

## 十一、第二阶段执行统计

| 指标 | 数值 |
|------|------|
| 新增集成测试类 | 2（OrderConcurrencyIT、RechargeConcurrencyIT） |
| 测试调用次数 | 6（每类 @RepeatedTest(3)） |
| 通过 / 失败 / 错误 / 跳过 | 6 / 0 / 0 / 0 |
| 并发请求总数 | 订单 150（50×3 轮）、充值回调 60（20×3 轮） |
| 原 12 个单元测试回归 | 12/12 通过（mvn test 全量回归 BUILD SUCCESS） |
| 证据文件 | target/surefire-reports/TEST-com.trade.virtual_trade.it.OrderConcurrencyIT.xml、TEST-com.trade.virtual_trade.it.RechargeConcurrencyIT.xml |

## 十二、简历成果更新（在第六章 B 版基础上可追加）

> - 编写基于 CountDownLatch 的**真实数据库并发集成测试**（非 Mockito），在独立 MySQL 8 隔离库中验证：**50 并发抢购同一商品 3 轮共 150 请求，每轮仅 1 笔订单落库、0 超卖、0 死锁**；**20 并发重复支付回调 3 轮共 60 请求，每轮余额仅入账一次、流水仅 1 条**，Surefire 报告 6/6 通过

**仍未覆盖**: 场景 C（并发余额支付一致性）与场景 D（支付回调 vs 超时取消竞争）尚无真实并发集成测试；无任何性能压测数据（不得虚构 QPS/TPS）。
