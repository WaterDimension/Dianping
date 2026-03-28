package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    // Lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 异步线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHander());
    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHander implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 1.获取队列中订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }

            }
        }
    }
    //异步处理订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户 这里是子线程，无法从thread UserHolder拿
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //获取锁失败， 返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        //获取成功，
        try {
            //事务，获取代理对象    这里同理底层是从ThreadLocal取,只能从当前类成员变量获取/主线程传过来
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
             proxy.creatVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();




    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //  1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1不是0, 没购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //TODO 保存阻塞队列
        //2.2 是0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");

        //2.3创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);   //订单id(主键), 由id生成器生成。

        //2.4存到阻塞队列
        orderTasks.add(voucherOrder);
        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getUserId()).count();
        //5.2判断是否存在
        if (count > 0){
            //用户购买过了
            log.error("您已经购买过一次了");
            return;
        }
        //6.扣减库存
        boolean success = seckillService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();  // ✅ 核心：只要库存大于0就可以扣.update();   //乐观锁，cas法
        //6.1库存不足
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//
//        //        Long userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);
//
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);   //订单id(主键), 由id生成器生成。
//
//        voucherOrderService.save(voucherOrder);
//        //返回订单id
//        return Result.ok(orderId);
    }
}
//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //1.查询优惠券信息
//        SeckillVoucher voucher = seckillService.getById(voucherId);
//        //2.判断秒杀是否开始
//        LocalDateTime begin = voucher.getBeginTime();
//        if (begin.isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        //3.判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足");
//        }
//
//        //5.一人一单
//        Long userId = UserHolder.getUser().getId();
//    //保证事务提交存入db后，再释放锁，才能确保线程安全
//        //这里是包装类对象，这里需要的是值一样，toString返回的是地址,
//        // intern去字符串常量池里找：如果池中已经有 "1001" → 直接返回池中的对象，如果没有 → 把 "1001" 放进池，再返回
//        //这使得同一个用户，不管new多少对象，
//        //不同用户，就不会被锁定
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.creatVoucherOrder(voucherId);
////        }
//        //redis分布式锁: 自己创建锁释放锁, setnx实现互斥
//        //创建锁对象
////        SimpleRedisLock lock1 = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //尝试获取锁，参数分别是：获取锁的最大等待时间(期间会重试)，锁自动释放时间，时间单位
//        boolean isLock = lock.tryLock(1,10, TimeUnit.SECONDS);
//        if (!isLock) {
//            //获取锁失败， 返回错误或重试
//            return Result.fail("一个人只允许下一单");
//        }
//        //获取成功，
//        try {
//            //事务，获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }

//    }

