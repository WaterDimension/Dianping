package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RequireLogin;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.sun.xml.internal.bind.v2.TODO;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;


import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;


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

    @Resource
    private IFollowService followService;

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
//            proxy = (BlogServiceImpl) AopContext.currentProxy();
            isBlogLiked(blog);
            queryBlogUser(blog);
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
//        proxy = (BlogServiceImpl) AopContext.currentProxy();

        // 2. 给 blog 填充 用户昵称、头像（直接修改原对象）
        queryBlogUser(blog);

        // 3. 给 blog 填充 是否点赞（直接修改原对象）
        isBlogLiked(blog);

        // 此时的 blog 已经是【完整数据】了！
        return Result.ok(blog);
    }

    //用户未登录情况下，不需要查询当前是否点过赞
//    @RequireLogin
    public void isBlogLiked(Blog blog) {
        // 获取登陆用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，无需查询是否点赞
            return;
        }
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
    /*
        博主发博客后，保存并推送blog到粉丝信箱
     */

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登陆用户(当前发笔记的人)
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.debug("========== 用户为空，发布失败！==========");
            return Result.fail("请先登录");
        }
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean success = save(blog);
        if(!success){
            return Result.ok("新增笔记失败");
        }
        // 3.查询博主的所有粉丝select * from tb_user where follow_user_id = userId;
        List<Follow> followUser = followService.query().eq("follow_user_id", user.getId()).list();

        // 4.推送给所有粉丝
        for(Follow follow : followUser){
            //获取粉丝id
            Long userId = follow.getUserId();
            // 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 3.返回id
        return Result.ok(blog.getId());
    }


@Override
public Result queryBlogOfFollow(Long max, Integer offset) {
    // 1.获取当前用户
    Long userId = UserHolder.getUser().getId();
    // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
    String key = FEED_KEY + userId;
    Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
            .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    // 3.非空判断
    if (typedTuples == null || typedTuples.isEmpty()) {
        return Result.ok();
    }
    // 4.解析数据：blogId、minTime（时间戳）、offset
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0; // 2
    int os = 1; // 2
    for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
        // 4.1.获取id
        ids.add(Long.valueOf(tuple.getValue()));
        // 4.2.获取分数(时间戳）
        long time = tuple.getScore().longValue();
        if(time == minTime){
            os++;
        }else{
            minTime = time;
            os = 1;
        }
    }
    os = minTime == max ? os : os + offset;
    // 5.根据id查询blog
    String idStr = StrUtil.join(",", ids);
    List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

    for (Blog blog : blogs) {
        // 5.1.查询blog有关的用户
        queryBlogUser(blog);
        // 5.2.查询blog是否被点赞
        isBlogLiked(blog);
    }

    // 6.封装并返回
    ScrollResult r = new ScrollResult();
    r.setList(blogs);
    r.setOffset(os);
    r.setMinTime(minTime);

    return Result.ok(r);
}
}
