package com.almond.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.almond.dto.Result;
import com.almond.entity.ShopType;
import com.almond.mapper.ShopTypeMapper;
import com.almond.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.almond.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.almond.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeOrderByAsc() {
        //1.从redis拿取数据,数据以list存储在redis中
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);
        //2.如果redis中有数据,直接返回
        if(StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            //将json转换为集合
            return Result.ok(typeList);
        }
        //3.如果redis中没有数据,到数据库中访问
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList == null) {
            return Result.fail("不存在该类型");
        }
        //4.向redis中加入typeList数据
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY, JSONUtil.toJsonStr(typeList),CACHE_SHOPTYPE_TTL, TimeUnit.HOURS);
        return Result.ok(typeList);
    }
}
