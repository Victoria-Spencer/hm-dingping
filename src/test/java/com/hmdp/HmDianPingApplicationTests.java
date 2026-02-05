package com.hmdp;

import com.hmdp.dto.ShopQueryTestDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void testBatchQueryShops() {
        // 批量查询缓存数据（缓存击穿策略）
        List<ShopQueryTestDTO> dtoList = new ArrayList<>();
        List<Long> idList = new ArrayList<>();
        idList.add(1L);
        idList.add(2L);
        idList.add(3L);
        for (Long shopId : idList) {
            ShopQueryTestDTO dto = new ShopQueryTestDTO();
            dto.setId(shopId);
            dtoList.add(dto);
        }
        Map<ShopQueryTestDTO, Shop> map = cacheClient.batchQueryWithPassThrough(
                (dto) -> CACHE_SHOP_KEY + dto.getId(),
                dtoList,
                Shop.class,
                missDtos -> {
                    // 步骤4.1：从DTO中提取店铺ID（批量查询的入参）
                    List<Long> missShopIds = missDtos.stream()
                            .map(ShopQueryTestDTO::getId)
                            .collect(Collectors.toList());

                    // 步骤4.2：批量查询数据库（支持多SQL封装）
                    // SQL1：批量查询店铺基础信息
                    List<Shop> shopList = shopMapper.selectBatchIds(missShopIds);
                    // （可选）SQL2：查询店铺关联数据（如优惠券、评分），封装到Shop中
                    // shopList.forEach(shop -> {
                    //     List<Voucher> vouchers = voucherMapper.selectByShopId(shop.getId());
                    //     shop.setVouchers(vouchers); // 需给Shop加vouchers字段
                    // });

                    // 步骤4.3：转换为「DTO → Shop」的Map（必须和入参DTO对应）
                    return missDtos.stream().collect(Collectors.toMap(
                            dto -> dto, // Key：原始DTO
                            dto -> shopList.stream()
                                    .filter(shop -> shop.getId().equals(dto.getId()))
                                    .findFirst()
                                    .orElse(null) // 无数据则返回null（触发缓存空值）
                    ));
                },
                // 参数5：缓存过期时间（30分钟）
                30L,
                // 参数6：时间单位（分钟）
                TimeUnit.MINUTES
        );

        // 过滤掉Shop为null的条目，再遍历打印
        map.entrySet().stream()
                .filter(entry -> entry.getValue() != null) // 只保留有数据的店铺
                .sorted(Comparator.comparing(entry -> entry.getKey().getId())) // 按店铺ID升序
                .forEach(entry -> {
                    ShopQueryTestDTO dto = entry.getKey();
                    Shop shop = entry.getValue();
                    System.out.println("=======================================");
                    System.out.println("店铺ID：" + dto.getId() + "，数据：" + shop);
                });
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id：" + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time："+ (end - begin));
    }

    /**
     * 导入店铺位置信息到Redis中
     */
    @Test
    void loadShopData() {
        // 1.从数据库中查询数据
        List<Shop> list = shopService.list();
        // 2.将店铺进行分组，将typeId一致的放到一个集合中
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批写入redis中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取分组的键
            String key = SHOP_GEO_KEY + entry.getKey();
            // 3.2.获取分组的店铺列表，封装成Redis GEO需要的GeoLocation集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>();
            for (Shop shop : value) {
                RedisGeoCommands.GeoLocation<String> location = new RedisGeoCommands.GeoLocation<String>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                );
                geoLocations.add(location);
            }
            // 3.3.批量写入
            if (!geoLocations.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, geoLocations);
            }
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];

        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999) {
                // 添加元素
                stringRedisTemplate.opsForHyperLogLog().add("hl3", values);
            }
        }

        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl3");
        log.info("count：{}", count);
    }
}
