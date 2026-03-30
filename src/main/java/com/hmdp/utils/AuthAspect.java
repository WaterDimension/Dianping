package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {
    // 切点：找到所有加了 @RequireLogin 的方法
    @Pointcut("@annotation(com.hmdp.utils.RequireLogin)")
    public void authPointcut() {}

    @Before("authPointcut()")
    public void checkLogin(JoinPoint joinPoint) {
        // 1.检查是否登陆
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            throw new RuntimeException("请先登录，查看页面详情");
        }
        // 2.@Before 无需手动执行方法，Spring 会自动执行
    }
}
