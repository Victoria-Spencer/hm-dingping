package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;

import java.time.Duration;

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
    @Autowired
    private HttpServletResponse httpServletResponse;

    /**
     * 根据id查询店铺信息
     * @param id
     * @return
     */
    public Shop queryShopById(Long id) {
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
