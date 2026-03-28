package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private String name;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识: UUID + 线程Id
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //set lock thread1 nx ex 10   (nx是互斥，ex是设置超时时间)
        //这里redis存的value 一石二鸟， 解决了释放锁时判断是不是自己所属的问题。
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //不用判断获取锁是否成功
        // 但返回包装类自动拆箱有风险，若是null,得返回false啊
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        //保持查锁的归属、判断 和 释放锁的原子性
        //使用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
//    @Override
//    public void unlock() {
//        //获取线程标识:UUID + 线程Id
//        String   = Thread.currentThread().getId() + ID_PREFIX;
//        //获取锁中的标识
//        String threadValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + threadId);
//        //一致才释放锁,解决了锁误删
//        if(threadId.equals(threadValue)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
