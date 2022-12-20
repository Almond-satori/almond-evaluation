package com.almond.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final long NUM_BIT = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = second - BEGIN_TIMESTAMP;
        //2.生成序列号num
        // 每天生成一个key,这样避免所有的自增id都在一个key里达到上限值
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 设置序号自增长,如果对应键没有值会自动创建一个,并返回给num
        long num = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
        return timeStamp << NUM_BIT | num;
    }
}
