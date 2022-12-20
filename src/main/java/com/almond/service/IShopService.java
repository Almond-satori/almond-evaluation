package com.almond.service;

import com.almond.dto.Result;
import com.almond.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    Result queryShopByTypeWithGeo(Integer typeId, Integer current, Double x, Double y);
}
