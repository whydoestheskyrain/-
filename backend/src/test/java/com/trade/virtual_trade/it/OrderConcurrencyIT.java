package com.trade.virtual_trade.it;

import com.trade.virtual_trade.entity.Order;
import com.trade.virtual_trade.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实数据库并发集成测试 —— 场景A：50 个买家同时抢购同一件上架商品。
 *
 * 与 Mockito 单元测试的区别：
 * 1. 使用独立 MySQL 8 库 trade_concurrency_test（结构复制自开发库，测试后清理数据）；
 * 2. 不 Mock 任何 Mapper，createOrder 完整经过 Spring 事务 -> MyBatis -> InnoDB 行锁；
 * 3. 每个并发线程持有独立连接与独立事务（Hikari 池 60 > 线程数 50，测试类本身不加 @Transactional）；
 * 4. 结论以数据库最终落库状态为准，不以方法返回值为准。
 */
@SpringBootTest
@ActiveProfiles("concurrency")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConcurrencyIT {

    private static final int THREADS = 50;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 记录每轮创建的测试数据，@AfterEach 安全清理 */
    private final List<Integer> createdUserIds = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> createdProductIds = Collections.synchronizedList(new ArrayList<>());

    /** 安全保护：只允许在隔离测试库上执行，防止误连开发库/生产库 */
    @BeforeAll
    void guardDatabase() {
        String db = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        System.out.println("[OrderConcurrencyIT] current database = " + db);
        assertEquals("trade_concurrency_test", db,
                "安全保护失败：并发测试只允许在隔离库 trade_concurrency_test 上执行");
    }

    @RepeatedTest(3)
    void fiftyBuyersCompeteForOneProduct(RepetitionInfo repetition) throws Exception {
        final int round = repetition.getCurrentRepetition();
        final String tag = "r" + round + "_" + System.currentTimeMillis();

        // ---------- 准备独立测试数据：1 个卖家 + 50 个买家 + 1 件上架商品 ----------
        final int sellerId = insertUser("conc_seller_" + tag);
        final List<Integer> buyerIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            buyerIds.add(insertUser("conc_b" + i + "_" + tag));
        }
        final int productId = insertProduct(sellerId, "CONC-PRODUCT-" + tag, new BigDecimal("99.00"));

        // ---------- 50 个线程同一时刻并发调用真实业务方法 createOrder ----------
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger businessReject = new AtomicInteger();
        AtomicInteger systemError = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Order order = new Order();
                    order.setOrderNo("CT" + tag + "_" + idx);
                    order.setBuyerId(buyerIds.get(idx));
                    order.setProductId(productId);
                    orderService.createOrder(order);
                    success.incrementAndGet();
                } catch (RuntimeException re) {
                    String msg = String.valueOf(re.getMessage());
                    if (msg.contains("抢先下单")) {
                        businessReject.incrementAndGet();
                    } else {
                        systemError.incrementAndGet();
                        errorSamples.add(re.getClass().getSimpleName() + ": " + msg);
                    }
                } catch (Exception e) {
                    systemError.incrementAndGet();
                    errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "50 个线程未在 30 秒内全部就绪");
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "50 个线程未在 120 秒内全部结束");
        pool.shutdown();

        // ---------- 以数据库最终状态为准核验 ----------
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE product_id=?", Integer.class, productId);
        Integer unpaidCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE product_id=? AND status=1", Integer.class, productId);
        Integer productStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id=?", Integer.class, productId);
        Integer winnerSeller = jdbcTemplate.queryForObject(
                "SELECT seller_id FROM `order` WHERE product_id=?", Integer.class, productId);

        System.out.printf("[OrderConcurrencyIT] round=%d threads=%d success=%d businessReject=%d systemError=%d "
                        + "dbOrders=%d dbUnpaid=%d productStatus=%d%n",
                round, THREADS, success.get(), businessReject.get(), systemError.get(),
                orderCount, unpaidCount, productStatus);
        if (!errorSamples.isEmpty()) {
            System.out.println("[OrderConcurrencyIT] error samples: " + errorSamples);
        }

        assertEquals(1, success.get(), "只允许 1 个请求下单成功");
        assertEquals(THREADS - 1, businessReject.get(), "其余请求必须被业务规则拒绝（商品已被其他用户抢先下单）");
        assertEquals(0, systemError.get(), "不允许出现死锁/锁超时等系统级异常");
        assertEquals(Integer.valueOf(1), orderCount, "数据库最终只能存在 1 笔订单（无超卖/无重复占用）");
        assertEquals(Integer.valueOf(1), unpaidCount, "唯一订单必须是待支付状态");
        assertEquals(Integer.valueOf(3), productStatus, "商品最终状态必须为 3（已被占用）");
        assertEquals(Integer.valueOf(sellerId), winnerSeller, "订单卖家必须取自数据库商品记录");
    }

    /** 安全清理：先删订单，再删商品，最后删用户（通知表随用户 ON DELETE CASCADE 级联删除） */
    @AfterEach
    void cleanup() {
        for (Integer pid : createdProductIds) {
            jdbcTemplate.update("DELETE FROM `order` WHERE product_id=?", pid);
        }
        for (Integer pid : createdProductIds) {
            jdbcTemplate.update("DELETE FROM product WHERE id=?", pid);
        }
        createdProductIds.clear();
        for (Integer uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user WHERE id=?", uid);
        }
        createdUserIds.clear();
    }

    private int insertUser(String username) {
        jdbcTemplate.update("INSERT INTO user(username,password,phone,nickname) VALUES(?,?,?,?)",
                username, "concurrency-test-placeholder", "13800000000", username);
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, username);
        assertNotNull(id);
        createdUserIds.add(id);
        return id;
    }

    private int insertProduct(int sellerId, String title, BigDecimal price) {
        // game_account/game_password 为 NOT NULL 无默认值列，测试占位值即可
        jdbcTemplate.update("INSERT INTO product(seller_id,title,price,status,game_type,game_account,game_password,"
                        + "create_time,update_time) VALUES(?,?,?,1,'','conc-test-account','conc-test-password',NOW(),NOW())",
                sellerId, title, price);
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM product WHERE title=?", Integer.class, title);
        assertNotNull(id);
        createdProductIds.add(id);
        return id;
    }
}
