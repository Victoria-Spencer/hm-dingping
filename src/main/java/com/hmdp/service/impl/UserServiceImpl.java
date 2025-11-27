package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送验证码
     * @param phone
     * @param session
     */
    public void sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            // 2.不符合，返回错误信息
            throw new RuntimeException("手机号格式错误！");
        }
        // 3.生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 5.发送验证码
        session.setAttribute("code", code);
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     */
    public void login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            throw new RuntimeException("手机号格式错误！");
        }
        // 2.校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();

        if(code == null || !code.equals(cacheCode.toString())) {
            throw new RuntimeException("验证码错误");
        }
        // 3.查询用户
        User user = query().eq("phone", phone).one();
        // 4.判断用户是否存在，存在，新增，并返回用户信息
        if(user == null) {
            user = createUserWithPhone(phone);
        }
        // 5.保存信息到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
