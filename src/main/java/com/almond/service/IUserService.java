package com.almond.service;

import com.almond.dto.LoginFormDTO;
import com.almond.dto.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import com.almond.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {
    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
