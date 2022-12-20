-- 获取redis锁的值(线程id),和当前线程id比较
-- 到redis中获取value,lua中下标从1开始
if(ARGV[1] == redis.call("get",KEYS[1])) then
    return redis.call("del",KEYS[1]) -- 删除成功返回为1
end
return 0; -- 删除失败返回为0