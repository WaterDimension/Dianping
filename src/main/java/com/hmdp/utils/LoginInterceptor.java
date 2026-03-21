package com.hmdp.utils;


import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
/*
 * <p>
 * 校验登陆拦截器
 * </p>
 */

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //前置中 1.获取session
        HttpSession session = request.getSession();

        //2. 获取session中的用户
        UserDTO userDTO = (UserDTO) session.getAttribute("user");

        //3.判断用户是否存在
        if(userDTO == null) {
            //4.不存在，拦截,返回状态码
            response.setStatus(401);
            return false;
        }

        //5.存在，放到Threadlocal
        UserHolder.saveUser(userDTO);
        System.out.println("nihaonihao!!!!!!!");
        //6.放行
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }

}
