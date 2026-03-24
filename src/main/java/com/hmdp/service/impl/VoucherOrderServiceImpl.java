package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillService;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillService.getById(voucherId);
        //2.判断秒杀是否开始
        LocalDateTime begin = voucher.getBeginTime();
        if (begin.isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足");
        }

        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //保证事务提交存入db后，再释放锁，才能确保线程安全
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result creatVoucherOrder(Long voucherId) {

        //这里是包装类对象，这里需要的是值一样，toString返回的是地址,
        // intern去字符串常量池里找：如果池中已经有 "1001" → 直接返回池中的对象，如果没有 → 把 "1001" 放进池，再返回
        //这使得同一个用户，不管new多少对象，
        //不同用户，就不会被锁定
            //5.一人一单
            Long userId = UserHolder.getUser().getId();
            //5.1查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //5.2判断是否存在
            if (count > 0){
                //用户购买过了
                return Result.fail("您已经购买过一次了");
            }
            //6.扣减库存
            boolean success = seckillService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0).update();  // ✅ 核心：只要库存大于0就可以扣.update();   //乐观锁，cas法
            //6.1库存不足
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setVoucherId(voucherId);

    //        Long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);

            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);   //订单id(主键), 由id生成器生成。

            voucherOrderService.save(voucherOrder);
            //返回订单id
            return Result.ok(orderId);

    }
}
