package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        // 1.判断到底是关注or取关
        String key = "follows:" + userId;
        if(isFollow) {
            // 2.关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }

        }
        //3.取关  delete from tb_follow where user_id = ? and follow_user_id = ?
        else {
//            query().eq("user_id", id).eq("follow_user_id", followUserId).remove()
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));

            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.查询是否关注 select * from tb where user_id = ? and follow_user_id = ?
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        //求的是目标用户followUserId和当前用户UserHolder.getUser()的共同关注
        Long userId = UserHolder.getUser().getId();

        //求交集
        String key = "follows:" + userId;
        String targetKey = "follows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, targetKey);

        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
