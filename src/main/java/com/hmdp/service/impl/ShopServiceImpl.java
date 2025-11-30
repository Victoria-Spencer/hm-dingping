package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 重试次数限制
    private static final int MAX_RETRY = 30;

    /**
     * 根据id查询店铺信息
     * @param id
     * @return
     */
    public Shop queryShopById(Long id) {
        // 缓存穿透
//        return queryWithPassThrough(id);
        // 缓存击穿
        return queryWithMutex(id, 1); // 初始重试次数1
    }

    private Shop queryWithMutex(Long id, int retryCount) {
        // 1.从redis中查询店铺
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断店铺是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 若命中，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // redis内容不为空，说明数据库中不存在该数据
        if (shopJson != null) {
            throw new RuntimeException("店铺不存在");
        }
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = UUID.randomUUID().toString(); // 用UUID标识线程
        Shop shop = null;

        try {
            // 3.缓存重建
            // 3.1.获取锁
            // 3.2.判断是否获取到锁
            boolean isLock = tryLock(lockKey, lockValue);
            if (!isLock) {
                if (retryCount >= MAX_RETRY) {
                    throw new RuntimeException("获取锁失败，请稍后重试");
                }
                Thread.sleep(50);
                return queryWithMutex(id, retryCount + 1);
            }
            // 3.3.成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延迟
            Thread.sleep(200);
            // 4.数据库中不存在，写入空值，报错
            if (shop == null) {
                // redis中缓存空值
                stringRedisTemplate.opsForValue().set(key, "", Duration.ofMinutes(CACHE_NULL_TTL));
                // 报错
                throw new RuntimeException("店铺不存在");
            }
            // 5.存在，写入redis中
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, jsonStr, Duration.ofMinutes(CACHE_SHOP_TTL));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6.释放锁
            unlock(lockKey, lockValue);
        }

        // 7.返回
        return shop;
    }

    private Shop queryWithPassThrough(Long id) {
        // 1.从redis中查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断店铺是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 若命中，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // redis内容不为空，说明数据库中不存在该数据
        if (shopJson != null) {
            throw new RuntimeException("店铺不存在");
        }

        // 3.未命中，查询数据库
        Shop shop = getById(id);
        // 4.数据库中不存在，写入空值，报错
        if (shop == null) {
            // redis中缓存空值
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", Duration.ofMinutes(CACHE_NULL_TTL));
            // 报错
            throw new RuntimeException("店铺不存在");
        }
        // 5.存在，写入redis中
        String jsonStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, jsonStr, Duration.ofMinutes(CACHE_SHOP_TTL));
        // 6.返回
        return shop;
    }

    /**
     * 获取分布式锁（带唯一value）
     */
    private boolean tryLock(String key, String value) {
        // value取随机值，删除线程时，用于区分线程归属
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(LOCK_SHOP_TTL));
        return BooleanUtil.isTrue(isLock);
    }

    /**
     * 释放分布式锁（Lua脚本保证原子性）
     */
    private void unlock(String key, String value) {
        // 可能误删其它线程
//        stringRedisTemplate.delete(key);
        String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else " +
                "return 0 " +
                "end";
        stringRedisTemplate.execute(
                new DefaultRedisScript<>(luaScript, Long.class),
                Collections.singletonList(key),
                value
        );
    }

    /**
     * 更新店铺信息
     * @param shop
     */
    @Transactional
    public void update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            throw new RuntimeException("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.redis中删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }
}
