package com.almond.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String LOCK_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //当一个线程获得锁,长时间阻塞以至于锁都过了ttl,另一线程进入,此时原线程释放锁,会产生错误
        //在key中放入线程id,保证不会释放另一个线程的锁
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + name,
                threadId,
                timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); //防止Boolean插箱后为null
    }


    /**
     * 使用lua脚本实现锁的判断以及释放,保证其原子性
     */
    @Override
    public void unlock() {
            stringRedisTemplate.execute(UNLOCK_SCRIPT,
                    Collections.singletonList(LOCK_PREFIX + name), //lua中key集合
                    ID_PREFIX+Thread.currentThread().getId() //lua中argv集合
            );
        }

//    @Override
//    public void unlock() {
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + name);
//        //检查当前要释放的锁是否是本线程的锁
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(LOCK_PREFIX + name);
//        }
//    }
}
