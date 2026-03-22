package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //1.尝试从Redis中查询店铺
        String key = CACHE_SHOP_TYPE_KEY;
        Map<Object, Object> shopTypeMap = stringRedisTemplate.opsForHash().entries(key);
        //2.判断缓存命中
        //2.1缓存命中：Hutool一键反序列化
        List<ShopType> shopTypeList = new ArrayList<>();
        if (!shopTypeMap.isEmpty()) {
            for (Object value : shopTypeMap.values()) {
                // Hutool JSONUtil 直接转对象，无需手动处理异常（底层已封装）
                ShopType shopType = JSONUtil.toBean(value.toString(), ShopType.class);
                shopTypeList.add(shopType);
            }
            // 按sort排序
            shopTypeList.sort((a, b) -> a.getSort() - b.getSort());
            return shopTypeList;
        }
        //2.2缓存查不到，查询数据库
        shopTypeList = query().orderByAsc("sort").list();

        // 4. 缓存回写：Hutool一键序列化
        for (ShopType shopType : shopTypeList) {
            // Hutool 直接转JSON字符串，无需try-catch
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForHash().put(key, shopType.getId().toString(), shopTypeJson);
        }
        log.debug("执行了redis-cache店铺类型查询");
        //5.返回店铺信息
        return shopTypeList;
    }
}
