package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Object queryById(Long id) {
        //1.尝试从Redis中查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.缓存命中
        if (StrUtil.isNotBlank(shopJson)) {
            //返回商铺信息
            JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shopJson);
        }
        //3.未命中
        //根据id查询数据库
        Shop shop = getById(id);
        //不存在，返回状态码
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        //商铺存在，写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        //4.返回店铺信息
        return Result.ok(shop);
    }
}
