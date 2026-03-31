package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;

import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;


import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    @Resource
    private  CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id1 -> getById(id1), CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        Shop shop1 = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 操作数据库，
     * 先操作db，后删除缓存
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        Long Id = shop.getId();
        if (Id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + Id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // x, y不一定会传进来
        // 1.判断：是否需要根据坐标查询
        if (x == null || y == null) {
            //不需要坐标查询， 按数据库查询
            // 不需要坐标查询 → 直接从 MySQL 分页查询
            Page<Shop> page = lambdaQuery()
                    .eq(Shop::getTypeId, typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回分页数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数      (current是页码)
        int from  = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_BATCH_SIZE;

        // 3.查询redis：按照举例排序、分页。 结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()  //geosearch key bylonlat x y byradius 10 withdistance
//                .search(
//                        key,
//                        GeoReference.fromCoordinate(x, y),  //new Point(shop.getX(), shop.getY())的静态方法
//                        new Distance(5000),
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)  //从0到end页
//                );
        // ========================
        // 【关键修复】全版本兼容！
        // ========================
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        key,
                        new Circle(x, y, 5000), // 经纬度 + 5000米范围
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()  // 返回距离
                                .sortAscending()     // 由近到远
                                .limit(end)          // 取到 end 条
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() <= from) {
            //没有下一页，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1截取 from ~ end的部分
        List<Long> ids = new ArrayList<>();
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2获取店铺id
            String ShopIdStr = result.getContent().getName();
            ids.add(Long.parseLong(ShopIdStr));
            // 4.3获取距离
            Distance distance = result.getDistance();
            distanceMap.put(ShopIdStr, distance);
        });

        // 5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                // 按Redis顺序排序
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
    /*   改成 内存排序 写法（直接替换）：
        // 1. 先查出来（会乱序）
            List<Shop> shops = listByIds(ids);

        // 2. 用 Java 内存重新排序（恢复 Redis 顺序）
            shops.sort(Comparator.comparing(shop -> ids.indexOf(shop.getId())));
      */
        // 6.给每个shop设置距离
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //返回
        return Result.ok(shops);
    }


}
