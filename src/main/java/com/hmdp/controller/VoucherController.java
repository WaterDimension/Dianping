package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
    /*
        这个方法是零时加的， 因为原来是手动添加热点key问题中的优惠券自动添加到了数据库和redis中， 由于我更新了redis，导致内容清空了，但是数据库信息还在，所以特此同步redis
     */
    // 临时预热所有秒杀库存
    @Resource
    private ISeckillVoucherService seckillVoucherService; // 注入这个！

    // 临时预热所有秒杀库存
    @GetMapping("/test/initStock")
    public Result initStock() {
        // 1. 查询所有秒杀券
        List<SeckillVoucher> list = seckillVoucherService.list();

        for (SeckillVoucher sv : list) {
            Long voucherId = sv.getVoucherId();
            Integer stock = sv.getStock();

            // 2. 写入 Redis 库存
            stringRedisTemplate.opsForValue()
                    .set("seckill:stock:" + voucherId, stock.toString());

            // 3. 初始化一人一单集合（修复：添加一个默认值，比如 0）
            stringRedisTemplate.opsForSet()
                    .add("seckill:order:" + voucherId, "0");
        }

        System.out.println("预热成功");
        return Result.ok("预热成功");
    }
}
