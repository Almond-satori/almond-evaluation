package com.almond.service;

import com.almond.dto.Result;
import com.almond.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeOrderByAsc();
}
