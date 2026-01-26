package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.lock.client.DistributedLockClient;
import com.hmdp.lock.core.DLock;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherServiceImpl;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IVoucherOrderService thisProxy;
    @Autowired
    private DistributedLockClient distributedLockClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    // 定义RedisScript对象（封装Lua脚本）
    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // Stream名称
    private static final String STREAM_KEY = "streams:order";
    // 消费组名称
    private static final String GROUP_NAME = "order-process-group";
    // 消费者名称（可动态生成，比如机器IP+线程ID）
    private static final String CONSUMER_NAME = "consumer-1";

    // ========== 项目启动时初始化消费者线程 ==========
    @PostConstruct
    public void init() {
        // 启动消费者线程，循环消费队列订单
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*// 阻塞队列：存放秒杀下单任务（设置容量为10000，避免OOM）
    private final BlockingQueue<VoucherOrder> orderTasks  = new LinkedBlockingQueue<>(10000);
    // 消费者任务：处理秒杀订单
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            // 循环消费：一直从队列取订单，直到线程池关闭
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 1. 从阻塞队列中取出秒杀订单（阻塞等待，直到有订单）
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 处理订单：扣减库存、生成订单、写入数据库
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("消费者线程被中断：{}", e.getMessage());
                    Thread.currentThread().interrupt(); // 重置中断标记，让循环退出
                } catch (Exception e) {
                    log.error("处理订单异常：{}", e.getMessage(), e);
                }
            }
        }
    }*/

    // 消费者任务：处理秒杀订单
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            // 循环消费：一直从队列取订单，直到线程池关闭
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 1.拉取消息：消费组模式，阻塞2秒（避免永久阻塞）
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(2000)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    // 2.无消息则继续循环
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.处理消息
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK确认：消息处理完成，从Pending列表移除
                    stringRedisTemplate.opsForStream().acknowledge(GROUP_NAME, record);
                } catch (Exception e) {
                    // 处理订单异常 -- PendingList
                    log.error("处理订单异常：{}", e.getMessage(), e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        // 循环消费：一直从队列取订单，直到线程池关闭
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1.拉取消息：消费组模式，阻塞2秒（避免永久阻塞）
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofMillis(2000)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                );
                // 2.无消息，说明没有异常消息，退出循环
                if (records == null || records.isEmpty()) {
                    break;
                }
                // 3.解析消息中的订单信息
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                // 4.处理消息
                handleVoucherOrder(voucherOrder);
                // 5.ACK确认：消息处理完成，从Pending列表移除
                stringRedisTemplate.opsForStream().acknowledge(GROUP_NAME, record);
            } catch (Exception e) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                log.error("处理订单异常：{}", e.getMessage(), e);
            }
        }
    }

    // 初始化Lua脚本（项目启动时加载）
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 修复3：项目关闭时优雅关闭线程池
    @PreDestroy
    public void destroy() {
        log.info("开始关闭秒杀订单处理线程池");
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            // 等待30秒，若仍未关闭则强制终止
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
        log.info("秒杀订单处理线程池已关闭");
    }


    /**
     * 下单秒杀优惠券（异步秒杀，提升接口吞吐量，牺牲了 “数据一致性” 和 “用户体验”）
     * @param voucherId
     * @return
     */
    public Long seckillVoucher(Long voucherId) throws InterruptedException {
        // ------------- 抢单业务 -----------------
        // 0. 获取用户ID
        Long userId = UserHolder.getUser().getId();
        // 预生成订单ID
        Long orderId = redisIdWorker.nextId("order");

        // 1.执行lua脚本
        long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );

        // 2.判断结果是否为0
        // 2.1.不为0，代表没有购买资格
        if (result != 0) {
            String msg = result == 1 ? "库存不足" : "请勿重复下单";
            throw new RuntimeException(msg);
        }

        // 2.2.为0，抢单成功
        // ------------------ 异步下单 -------------------
        /*// 3.秒杀成功： + 放入阻塞队列（异步下单）
        // 预生成订单ID
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1.订单ID
        voucherOrder.setId(orderId);
        // 3.2.用户ID
        voucherOrder.setUserId(userId);
        // 3.3.代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 3.4.放到阻塞队列中
        boolean offerSuccess = orderTasks.offer(voucherOrder);
        if (!offerSuccess) {
            log.warn("秒杀订单队列已满，voucherId={}, userId={}", voucherId, userId);
            throw new RuntimeException("当前秒杀人数过多，请稍后重试");
        }*/

        // 3.返回订单id
        return orderId;
    }

//    *
//     * 下单秒杀优惠券
//     * @param voucherId
//     * @return
//
//    public Long seckillVoucher(Long voucherId) throws InterruptedException {
//        // 1.查询秒杀优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherServiceImpl.getById(voucherId);
//        // 2.判断是否不在下单时间内
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())
//                ||  seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            throw new RuntimeException("当前不在抢购优惠券的时间内！");
//        }
//
//        // 3.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            throw new RuntimeException("库存不足！");
//        }*/
//
//        Long userId = UserHolder.getUser().getId();
//        synchronized((userId.toString().intern())) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return thisProxy.createVoucherOrder(voucherId);
//        }
//        // 用数据库分布式锁执行秒杀（锁key：业务+资源ID）
//        return dbDistributedLock.executeWithLock(
//                "seckill:voucher:" + voucherId + "user:id" + userId,
//                () -> thisProxy.createVoucherOrder(voucherId) // 秒杀业务逻辑
//        );
//        // 用redis分布式锁执行秒杀
////        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
////        DLock lock = distributedLockClient.getLock("lock:order" + userId);
////        boolean isLock = lock.tryLock();
////        lock.lockInterruptibly(-1L, TimeUnit.SECONDS);
//        boolean isLock = lock.tryLock(40L, -1, TimeUnit.SECONDS);
////        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            throw new RuntimeException("不允许重复下单");
//        }
//
//        try {
////            Thread.sleep(30000);
//            return thisProxy.createVoucherOrder(voucherId);
//        } finally {
////            System.out.println("资源已经释放了！");
//            lock.unlock();
//        }
//    }

    /*@Transactional
    public Long createVoucherOrder(Long voucherId) {
        // 4.一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经购买过了
            throw new RuntimeException("用户已经购买过一次！");
        }

        // 5.扣减库存
        boolean success = seckillVoucherServiceImpl.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 乐观锁解决超卖问题
                .update();
        if (!success) {
            // 扣减失败
            throw new RuntimeException("扣减失败");
        }

        // 6.构建下单条件
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户ID
        voucherOrder.setUserId(userId);
        // 6.3.代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 创建订单
        save(voucherOrder);

//        testReentrantLock();

        // 7.返回订单ID
        return orderId;
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经购买过了
            throw new RuntimeException("用户已经购买过一次！");
        }

        // 2.扣减库存
        boolean success = seckillVoucherServiceImpl.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 乐观锁解决超卖问题
                .update();
        if (!success) {
            // 扣减失败
            throw new RuntimeException("扣减失败");
        }

        // 2.创建订单
        save(voucherOrder);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();
        // 用redis分布式锁执行秒杀
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new RuntimeException("不允许重复下单");
        }

        try {
            thisProxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /*private void testReentrantLock() {
        Long userId = UserHolder.getUser().getId();
        // 用redis分布式锁执行秒杀
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        boolean isLock = lock.tryLock(1200L);

        if (!isLock) {
            throw new RuntimeException("不可重入");
        }

        try {
            System.out.println("可重入");
        } finally {
            lock.unlock();
        }
    }*/

    /*private void testReentrantLock() throws Exception {
        Long userId = UserHolder.getUser().getId();

        dbDistributedLock.executeWithLock(
                "seckill:voucher:" + "1010" + "user:id" + userId,
                () -> countTime()
        );
    }

    private Long countTime() {
        return 10L;
    }*/
}
