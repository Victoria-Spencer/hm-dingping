package com.hmdp.utils;

public final class RedisConstants {

    private RedisConstants() {}

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 180L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type";

    public static final String LOCK_PREFIX = "lock:";
    public static final Long LOCK_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
//    public static final String USER_SIGN_KEY = "sign:";
    /**
     * 签到Key模板：user:sign:用户ID:年月（sign:1001:202602）
     */
    public static final String USER_SIGN_KEY = "sign:%d:%s";
    /**
     * 连续签到天数位段起始偏移：31（避开0~30的日期偏移，对应1~31号）
     */
    public static final int CONTINUE_SIGN_OFFSET = 31;
    /**
     * 连续签到天数存储的位长度：u5（5位无符号数，范围0~31，满足业务需求）
     */
    public static final int CONTINUE_SIGN_BIT_LEN = 5;
}
