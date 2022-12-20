package com.almond.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.almond.dto.Result;
import com.almond.entity.Shop;
import com.almond.mapper.ShopMapper;
import com.almond.service.IShopService;
import com.almond.utils.CacheClient;
import com.almond.utils.RedisData;
import com.almond.utils.SystemConstants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.almond.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存击穿问题
//        函数作为参数 参数arg 返回值getById(arg)
//        cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class
//        , arg->getById(arg), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 使用逻辑时间解决缓存击穿
        Shop shop = cacheClient.queryByIdWithLogicalExpireTime(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class
                , arg -> getById(arg), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    /**
     * 在一个事务中实现对商店数据库的更新,以及缓存的删除
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        //1.根据id更新数据库
        Long id = shop.getId();
        if(id == null){
            Result.fail("商店id不能为空");
        }
        //2.更新数据库
        updateById(shop);
        //3.删除旧的缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByTypeWithGeo(Integer typeId, Integer current, Double x, Double y) {
        //如果没有请求地理信息,则直接到数据库中查询
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //带有地理信息的查询
        // 计算分页查询的起始位置
        int start = (current - 1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis中的地理信息
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y), //坐标原点
                new Distance(5000), // 查询半径
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end) //设置返回距离信息以及查询到第end条数据
        );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size() <= start){ //本页数据为空
            return Result.ok(Collections.emptyList());
        }
        //截取start-end部分
        ArrayList<Long> ids = new ArrayList<>();
        Map<String, Distance> distanceMap = new HashMap<>();
        content.stream().skip(start).forEach(result ->{
            String shopIdStr = result.getContent().getName();
            Distance distance = result.getDistance();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        shops.forEach(shop -> {
            shop.setDistance(distanceMap.get(shop.getId().toString())
                    .getValue());
        });
        //返回
        return Result.ok(shops);
    }

//    /**
//     * 使用互斥锁解决缓存击穿的查询方法,也解决缓存穿透
//     * @param id
//     * @return
//     */
//    private Shop queryByIdWithMutex(Long id){
//        String shopKey = CACHE_SHOP_KEY+id;
//        //1.从redis中查询店铺信息,店铺信息以json存储
//        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
//        //2.判断是否查到店铺
//        if(StrUtil.isNotBlank(shopJson)){ //redis中有店铺
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //在redis中放入null值解决缓存穿透问题,上一步判断了字符串不为null,若查到的数据是空字符串直接返回
//        if("".equals(shopJson)){
//            return null;
//        }
//        String lockKey = null;
//        Shop shop = null;
//        try {
//            //一个线程查询数据库即可,使用互斥锁保证
//            lockKey = LOCK_SHOP_KEY + id;
//            if(!tryLock(lockKey)){
//                //睡眠50ms,再次尝试获取锁
//                Thread.sleep(50);
//                return queryByIdWithMutex(id);
//            }
//            //获取了锁,查看redis中是否已经有缓存了
//            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
//            if(StrUtil.isNotBlank(shopJson)){ //redis中有店铺
//                return JSONUtil.toBean(shopJson, Shop.class);
//            }
//            //3.redis中没有,查询数据库
//            shop = getById(id);
//            //模拟查询延时
//            Thread.sleep(200);
//            //4.数据库中没有该店铺,返回错误信息
//            if(shop == null) {
//                //将空值写到redis中,防止缓存穿透
//                stringRedisTemplate.opsForValue()
//                        .set(shopKey, JSONUtil.toJsonStr(""),CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //5.将该店铺信息放入redis中
//            stringRedisTemplate.opsForValue()
//                    .set(shopKey, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException();
//        } finally {
//            //释放互斥锁
//            unlock(lockKey);
//        }
//
//        //6.向前端返回信息
//        return shop;
//    }

//    //线程池,提供redis数据的重建
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    /**
//     * 利用逻辑过期时间解决缓冲击穿问题
//     * @param id
//     * @return
//     */
//    private Shop queryByIdWithExpireTime(Long id){
//        String shopKey = CACHE_SHOP_KEY+id;
//        //1.从redis中查询店铺信息,店铺信息以json存储
//        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
//        //2.判断是否查到店铺
//        if(StrUtil.isBlank(shopJson)){
//            return null; //redis中没有该店铺,说明该店铺不是热点店铺(我们事先对热点店铺做了预热)
//        }
//        //3.在redis中放入null值解决缓存穿透问题,上一步判断了字符串不为null,若查到的数据是空字符串直接返回
//        if("".equals(shopJson)){
//            return null;
//        }
//        //4.1 将shopJson转为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //未知data的类型,上述语句会将其转换为JSONObject
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        //4.2 判断在逻辑上是否过期
//        if (LocalDateTime.now().isBefore(expireTime)){
//            //4.3 未过期,直接返回店铺信息
//            return shop;
//        }
//        // 4.4 过期,获取互斥锁,开启一个线程更新redis,并返回旧数据
//        // 尝试获取锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        if (tryLock(lockKey)){
//            //检查拿到锁后的缓存是否过期(可能在本线程获取锁的过程中,其他线程已经完成了数据重建)
//            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
//            redisData = JSONUtil.toBean(shopJson, RedisData.class);
//            expireTime = redisData.getExpireTime();
//            if (LocalDateTime.now().isBefore(expireTime)){
//                // 未过期,直接返回店铺信息
//                return shop;
//            }
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                //重建缓存后释放锁
//                try {
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        //未获得锁则直接返回旧数据
//        //6.向前端返回信息
//        return shop;
//    }
//
//    /**
//     * 存储shop到redis作为预热,并提供过期时间
//     * @param id
//     */
    public void saveShop2Redis(Long id,Long expireTime){
//        try {
//            Thread.sleep(200);//模拟更新耗时
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        // 从数据库获取数据
        Shop shop = getById(id);
        // 因为我们需要逻辑过期时间,这个值包含在一个类中
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime)); //过期时刻=当前时间+过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
//
//    /**
//     * 获取redis互斥锁
//     * setIfAbsent对应setnx,只有在redis中没有这个值时才能set
//     * @param key
//     * @return
//     */
//    private boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate
//                .opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag); //自动拆箱,并防止flag为null空指针异常
//    }
//
//    /**
//     * 释放互斥锁
//     * @param key
//     */
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }



}
