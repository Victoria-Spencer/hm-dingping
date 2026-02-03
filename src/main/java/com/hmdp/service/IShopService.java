package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询店铺信息
     * @param id
     * @return
     */
    Shop queryShopById(Long id);

    /**
     * 更新店铺信息
     * @param shop
     */
    void update(Shop shop);

    /**
     * 根据店铺类型查询店铺信息
     * @return
     */
    List<Shop> queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
