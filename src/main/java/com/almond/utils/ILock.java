package com.almond.utils;

public interface ILock {

    /**
     * 获取锁
     * @param timeoutSec 过期时间
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
