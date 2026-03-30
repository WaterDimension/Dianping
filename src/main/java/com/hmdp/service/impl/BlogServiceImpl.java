package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RequireLogin;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.sun.xml.internal.bind.v2.TODO;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    BlogServiceImpl proxy;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
//        records.forEach(blog -> {
//            this.queryBlogUser(blog);
//            this.isBlogLiked(blog);
//        });

        // 遍历，给每个blog设置是否点赞
        for (Blog blog : records) {
            // ❌ 错误写法：this.isBlogLiked(blog); 内部调用，绕过代理，AOP不生效
            // ✅ 正确写法：从Spring上下文获取代理对象调用
            proxy = (BlogServiceImpl) AopContext.currentProxy();
            proxy.isBlogLiked(blog);
            proxy.queryBlogUser(blog);
        }
        return Result.ok(records);
    }
    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);  //访问的博客对应用户
        // ✅ 加非空判断！！！
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    @Override
    public Result queryById(Long id) {
        // 1. 查询 blog（只有数据库字段：id、title、liked、userId...）
        Blog blog = getById(id);

        //queryById 内部调用 isBlogLiked() 会导致 AOP 不生效。
        // 内部 this.isBlogLiked() 会绕过代理，导致未登录时也会执行 isBlogLiked()
        // 前提：启动类必须加 @EnableAspectJAutoProxy(exposeProxy = true
        proxy = (BlogServiceImpl) AopContext.currentProxy();

        // 2. 给 blog 填充 用户昵称、头像（直接修改原对象）
        proxy.queryBlogUser(blog);

        // 3. 给 blog 填充 是否点赞（直接修改原对象）
        proxy.isBlogLiked(blog);

        // 此时的 blog 已经是【完整数据】了！
        return Result.ok(blog);
    }

    //用户未登录情况下，不需要查询当前是否点过赞
    @RequireLogin
    public void isBlogLiked(Blog blog) {
//        // 获取登陆用户
//        UserDTO user = UserHolder.getUser();
//        if (user == null) {
//            //用户未登录，无需查询是否点赞
//            return;
//        }
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long blogId) {
        // 获取登陆用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否点赞
        String key = BLOG_LIKED_KEY + blogId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // 没点赞，+1, 加到redis的set集合， 数据库+1
        if(score == null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
            // 保存用户到Redis的set集合 zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        // 点过了，-1， 从redis移除， 数据库 -1
        else {
            boolean success = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if(success){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikesById(Long id) {
        // 1.查修top5点赞用户zrang key 0 4
        String key =  BLOG_LIKED_KEY + id;
        Set<String> topId = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if(topId == null || topId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中的用户id
        List<Long> ids = topId.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据id查询用户
//        List<UserDTO> userDTOS = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        //  MySQL查询用户（顺序被打乱）
        //  NOTE 关键：按照Redis的正确顺序重新排序
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream()
                .sorted(Comparator.comparing(user -> ids.indexOf(user.getId())))
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }
}
