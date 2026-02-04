package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

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
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 年月格式化器（yyyyMM，如202602）
    private static final DateTimeFormatter YEAR_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    // 日期格式化器（dd，仅取当月日期）
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("d");

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
        // TODO temp
        String code = "111111";
//        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, Duration.ofMinutes(LOGIN_CODE_TTL));
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * return
     */
    public String login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            throw new RuntimeException("手机号格式错误！");
        }
        // 2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        if(code == null || !code.equals(cacheCode)) {
            throw new RuntimeException("验证码错误");
        }
        // 3.查询用户
        User user = query().eq("phone", phone).one();
        // 4.判断用户是否存在，存在，新增，并返回用户信息
        if(user == null) {
            user = createUserWithPhone(phone);
        }

        // 5保存用户到redis中
        // 5.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 5.2.将user转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString())
        );

        // 5.3.存储
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, Duration.ofMinutes(LOGIN_USER_TTL));
        // 6.返回token
        return token;
    }

    /**
     * 共同关注功能
     * @param id
     * @return
     */
    public List<UserDTO> followCommons(Long id) {
        // 1.生成key
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;

        // 2.查交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty() ) {
            return Collections.emptyList();
        }
        List<Long> ids = intersect.stream()
                .filter(str -> str.matches("\\d+")) // 正则匹配纯数字
                .map(Long::valueOf)
                .collect(Collectors.toList());

        // 3.根据ids查询用户信息，并转为UserDTO返回
        return listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public void sign() {
        // 1.获取当前用户
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        LocalDate today = LocalDate.now();
        String signKey = buildSignKey(userId, today);
        int dayOffset = getDayOffset(today);

        Boolean oldBit = stringRedisTemplate.opsForValue().setBit(signKey, dayOffset, true);
        if(Objects.equals(oldBit, true)) {
            log.info("重复签到！");
        }
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

    /**
     * 构建用户当月签到Key
     * @param userId 用户ID
     * @param date 日期（任意日期，自动提取年月）
     * @return 签到Key
     */
    private String buildSignKey(Long userId, LocalDate date) {
        String yearMonth = date.format(YEAR_MONTH_FORMAT);
        return String.format(USER_SIGN_KEY, userId, yearMonth);
    }

    /**
     * 计算日期对应的Bitmap位偏移量（偏移0对应当月1号，偏移n对应n+1号）
     * @param date 日期
     * @return 位偏移量（0~30）
     */
    private int getDayOffset(LocalDate date) {
        return date.getDayOfMonth() - 1;
    }
}
