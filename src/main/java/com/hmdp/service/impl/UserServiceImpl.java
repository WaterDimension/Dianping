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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
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
        //TODO 确保userMap的每个属性都变成string类型  stringredis extends redistemplate<string,string>
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //4.4设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
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
