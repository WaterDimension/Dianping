package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误qaq！");
        }

        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到redis
        //键phone前加业务前缀区分,值code
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送验证码成功！验证码：{}", code);
        System.out.println("发送成功，验证码：{}"+ code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号、验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误！");
        }

        //获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode(); //浏览器接收
        if (cacheCode == null || !code.equals(cacheCode)) {  //没发出验证码or验证码过期=null
            return Result.fail("验证码错误！");
        }
        //2.根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //3.如果用户不存在，创建新用户
        if (user == null) {
             user = createUserWithPhone(phone);
        }
        //4.存在/不存在，都保存到redis
        //4.1生成一个token，作为令牌
        String token = UUID.randomUUID().toString(true);

        //4.2将User对象转换为Hash存储
        //但是想要不显示pwd等信息，直传dto就好，得转换一下。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        //4.3存储
        String tokenKey = LOGIN_USER_KEY + token;
        //java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.String
        //NOTE 确保userMap的每个属性都变成string类型  stringredis extends redistemplate<string,string>
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //4.4设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入redis setbit key offset 1/0    offset第一天是0
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    //获取当前的连续签到天数
    @Override
    public Result signCount() {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        // 4.获取本月截止到今天的所有签到记录， 返回的是一个十进制的数字
        int dayOfMonth = now.getDayOfMonth();    //bitfiled sign:3:2026 get u 31 0   获取0~31号的记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if (result.isEmpty() || result == null){
            // 没有任何签到结果
            return Result.ok(0);
        }

        int count = 0;
        Long num = result.get(0);
        if (num == 0 || num == null){
            return Result.ok(0);
        }

        while (true) {
            if ((num & 1) == 0) {
                //如果为0， 说明未签到，结束
                break;
            }else {
                count ++;
            }
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建新用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2. 保存用户
        save(user);
        return user;
    }

}
