package com.almond.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.almond.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.almond.utils.RedisConstants.LOGIN_USER_KEY;
import static com.almond.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    //拦截器是在容器创建之前创建,没法直接注入StringRedisTemplate,因此在注册类中注入并传给拦截器
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取token,放在请求头的authorization中
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) return true;
        //2.获取redis中的用户
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.判断用户是否存在,如果不存在则拦截用户
        if(userMap.isEmpty()) {
            //未登录直接放行
            return true;
        }
        //4.将userMap转换为userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5.存在,保存用户到ThreadLocal中,Tomcat为每个请求单独创建一个线程区存放变量,以避免并发
        UserHolder.saveUser(userDTO);
        //6.刷新token有效时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //在ThreadLocal中销毁用户,防止占用tomcat内存
        UserHolder.removeUser();
    }
}
