package com.almond.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.almond.utils.RedisConstants.*;

@Component
public class CacheClient {

    //注意IOC是使用new对象的方式来实现注入的,因此工具方法没法使用static
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 向redis中存入数据
     * @param key 存入的键
     * @param value 存入的对象
     * @param time 过期时间
     * @param unit 过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 存入数据并设置逻辑过期
     * @param key 存入的键
     * @param value 存入的对象
     * @param time 存入的逻辑过期时间
     * @param unit 存入的逻辑过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透的工具类
     * @param keyPrefix redis中存储对象的键
     * @param id 存储对象id
     * @param type 存储对象的类对象
     * @param dbFallback 通过id查询数据库的函数
     * @param time 过期时间
     * @param unit 过期时间单位
     * @param <R> 返回的对象的类型
     * @param <ID> id的类型
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID,R> dbFallback,
                                          Long time,TimeUnit unit){
        String key = keyPrefix+id;
        //1.从redis中查询信息返回为json
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否查到信息,有信息直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //查看信息是否是空串,是空串则为非法的查询,返回null
        if("".equals(json)){
            return null;
        }
        //查询数据库,工具类方法中无法解决,使用函数式编程,调用方法参数中的函数来解决
        R r = dbFallback.apply(id);
        if(r == null){
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在,写入redis
        this.set(key, r,time, unit);
        //6.向前端返回信息
        return r;
    }


    //线程池,提供redis数据的重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用逻辑时间解决缓冲穿透
     * @param keyPrefix 对象的key前缀
     * @param lockKeyPrefix 互斥锁的key前缀
     * @param id id值
     * @param type 对象类型
     * @param dbFallback 按id查询数据库的函数
     * @param time 过期时间
     * @param unit 过期时间单位
     * @param <R> 返回值类型
     * @param <ID> ID类型
     * @return
     */
    public <R,ID> R queryByIdWithLogicalExpireTime(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback,
                                            Long time, TimeUnit unit){
        String key = keyPrefix+id;
        //1.从redis中查询信息,返回为json
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否查到店铺
        if(StrUtil.isBlank(json)){
            return null; //redis中没有该数据,说明该其不是热点数据(我们事先对热点数据做了预热)
        }
        //3.在redis中放入null值解决缓存穿透问题,上一步判断了字符串不为null,若查到的数据是空字符串直接返回
        if("".equals(json)){
            return null;
        }
        //4.1 将json转为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未知data的类型,先拿到Object类型的date,将其转换为json,在将json转换为真实类型
        Object data = redisData.getData();
        String dataJson = JSONUtil.toJsonStr(data);
        R r = JSONUtil.toBean(dataJson, type);
        //4.2 判断在逻辑上是否过期
        if (LocalDateTime.now().isBefore(expireTime)){
            //4.3 未过期,直接返回店铺信息
            return r;
        }
        // 4.4 过期,获取互斥锁,开启一个线程更新redis,并返回旧数据
        // 尝试获取锁
        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)){
            //检查拿到锁后的缓存是否过期(可能在本线程获取锁的过程中,其他线程已经完成了数据重建)
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            expireTime = redisData.getExpireTime();
            if (LocalDateTime.now().isBefore(expireTime)){
                // 未过期,直接返回店铺信息
                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存后释放锁
                try {
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, JSONUtil.toJsonStr(r1), time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //数据过期就直接返回旧数据
        return r;
    }

    /**
     * 获取redis互斥锁
     * setIfAbsent对应setnx,只有在redis中没有这个值时才能set
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate
                .opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); //自动拆箱,并防止flag为null空指针异常
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
