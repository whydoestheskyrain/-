package com.trade.virtual_trade.it;

import com.trade.virtual_trade.entity.RechargeOrder;
import com.trade.virtual_trade.mapper.RechargeOrderMapper;
import com.trade.virtual_trade.service.RechargeService;
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
 * 真实数据库并发集成测试 —— 场景B：同一笔充值订单的支付宝回调被并发重复投递。
 *
 * 验证 markSuccessIfPending（UPDATE ... WHERE status=0）在 InnoDB 行锁下的原子性：
 * 20 个线程同时回调同一订单号，只允许 1 个真正入账，余额只能增加一次。
 * 所有线程携带相同 out_trade_no 与相同 trade_no，模拟支付宝真实的重复通知。
 */
@SpringBootTest
@ActiveProfiles("concurrency")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RechargeConcurrencyIT {

    private static final int THREADS = 20;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Autowired
    private RechargeService rechargeService;

    @Autowired
    private RechargeOrderMapper rechargeOrderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Integer> createdUserIds = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> createdRechargeIds = Collections.synchronizedList(new ArrayList<>());

    /** 安全保护：只允许在隔离测试库上执行 */
    @BeforeAll
    void guardDatabase() {
        String db = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        System.out.println("[RechargeConcurrencyIT] current database = " + db);
        assertEquals("trade_concurrency_test", db,
                "安全保护失败：并发测试只允许在隔离库 trade_concurrency_test 上执行");
    }

    @RepeatedTest(3)
    void duplicateNotifyCreditsBalanceOnlyOnce(RepetitionInfo repetition) throws Exception {
        final int round = repetition.getCurrentRepetition();
        final String tag = "r" + round + "_" + System.currentTimeMillis();

        // ---------- 准备独立测试数据：1 个余额为 0 的用户 + 1 笔待支付充值单 ----------
        final int userId = insertUser("conc_rc_" + tag);
        RechargeOrder order = new RechargeOrder();
        final String orderNo = "RC" + tag;
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAmount(AMOUNT);
        order.setPaymentMethod("alipay");
        rechargeOrderMapper.insert(order);
        assertNotNull(order.getId());
        createdRechargeIds.add(order.getId());
        final String tradeNo = "TRADE" + tag;

        // ---------- 20 个线程同一时刻并发回调同一订单 ----------
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger returnTrue = new AtomicInteger();
        AtomicInteger returnFalse = new AtomicInteger();
        AtomicInteger systemError = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    boolean result = rechargeService.handleAlipayNotify(orderNo, tradeNo);
                    if (result) {
                        returnTrue.incrementAndGet();
                    } else {
                        returnFalse.incrementAndGet();
                    }
                } catch (Exception e) {
                    systemError.incrementAndGet();
                    errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "20 个线程未在 30 秒内全部就绪");
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "20 个线程未在 120 秒内全部结束");
        pool.shutdown();

        // ---------- 以数据库最终状态为准核验 ----------
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM recharge_order WHERE id=?", Integer.class, order.getId());
        String storedTradeNo = jdbcTemplate.queryForObject(
                "SELECT trade_no FROM recharge_order WHERE id=?", String.class, order.getId());
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user WHERE id=?", BigDecimal.class, userId);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_log WHERE user_id=? AND type='recharge'", Integer.class, userId);
        BigDecimal logSum = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(amount),0) FROM transaction_log WHERE user_id=? AND type='recharge'",
                BigDecimal.class, userId);

        System.out.printf("[RechargeConcurrencyIT] round=%d threads=%d returnTrue=%d returnFalse=%d systemError=%d "
                        + "status=%d balance=%s rechargeLogs=%d logSum=%s%n",
                round, THREADS, returnTrue.get(), returnFalse.get(), systemError.get(),
                status, balance, logCount, logSum);
        if (!errorSamples.isEmpty()) {
            System.out.println("[RechargeConcurrencyIT] error samples: " + errorSamples);
        }

        assertEquals(THREADS, returnTrue.get(), "重复通知应幂等返回 true（不影响外部回调方）");
        assertEquals(0, returnFalse.get(), "订单存在且处理成功时不应返回 false");
        assertEquals(0, systemError.get(), "不允许出现死锁/锁超时等系统级异常");
        assertEquals(Integer.valueOf(1), status, "充值订单最终状态必须为 1（支付成功）");
        assertEquals(tradeNo, storedTradeNo, "交易号必须被正确落库");
        assertNotNull(balance);
        assertEquals(0, balance.compareTo(AMOUNT), "余额必须只入账一次，实际余额=" + balance);
        assertEquals(Integer.valueOf(1), logCount, "充值流水只能有一条，实际=" + logCount);
        assertNotNull(logSum);
        assertEquals(0, logSum.compareTo(AMOUNT), "充值流水总金额必须等于单次充值金额，实际=" + logSum);
    }

    /** 安全清理：流水 -> 充值单 -> 用户 */
    @AfterEach
    void cleanup() {
        for (Integer uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM transaction_log WHERE user_id=?", uid);
        }
        for (Long rid : createdRechargeIds) {
            jdbcTemplate.update("DELETE FROM recharge_order WHERE id=?", rid);
        }
        createdRechargeIds.clear();
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
}
