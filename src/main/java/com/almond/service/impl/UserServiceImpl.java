package com.almond.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.almond.dto.LoginFormDTO;
import com.almond.dto.Result;
import com.almond.dto.UserDTO;
import com.almond.utils.CacheClient;
import com.almond.utils.RegexUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.almond.entity.User;
import com.almond.mapper.UserMapper;
import com.almond.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.almond.utils.RedisConstants.*;
import static com.almond.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号是否合法
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //2.手机号非法,返回错误信息
        if(phoneInvalid) return Result.fail("手机号格式非法!");
        //3.手机号合法,生成相应的验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码放在Redis中,键为手机号,值为验证码,设置验证码过期时间为2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.将验证码发送到对应手机,这里暂时显示在控制台中
        log.info("验证码发送成功,验证码为:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号是否合法
        String phone  = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号非法!");
        // 2.验证验证码是否正确
        String code = loginForm.getCode();
        String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(redisCode==null || !redisCode.equals(code))
            return Result.fail("验证码错误!");
        // 3.查看手机号是否绑定了用户 select * from tb_user where phone = ?
        User user = query().eq(PHONE, phone).one();
        if(user == null){ //该手机号未绑定用户
            user = createUserWithPhone(phone);
        }
        //4.保存用户信息到redis,用redis充当session,向Redis中只存储UserDTO所含数据,用户敏感信息放在数据库里
        //Redis中key为token(相当于jsessionid),value为用户
        // 4.1 生成随机令牌以及数据传输对象
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 4.2 将UserDTO对象转换为HashMap,保证id字段转换为String类型(StringRedisTemplate只能传String,而id是Long)
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        //使用putAll一个token对应一个userMap
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置token有效期1,采用与session相同的30min
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL,TimeUnit.MINUTES);
        //将token返回给前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //在数据库保存user
        save(user);
        return user;
    }
}
