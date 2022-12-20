--1.获取优惠券id
local voucherId = ARGV[1]
--2. 用户id,判断用户是否多次下单
local userId = ARGV[2]
-- 获取订单id
local orderId = ARGV[3]

--3.拼接指定优惠券库存量的key
local stockKey = 'seckill:stock:' .. voucherId
--4.拼接指定优惠券购买用户的key(一对多)
local userKey = 'seckill:order:' .. voucherId

--5.业务
if(tonumber(redis.call('get',stockKey))<=0) then
    -- 库存不足,返回1
    return 1
end
if(redis.call('sismember',userKey,userId)==1) then
    -- 不能重复购买,返回2
    return 2
end
--正常创建订单返回0
redis.call('incrby',stockKey,-1)
redis.call('sadd',userKey,userId)
-- 在队列中放入订单消息,参数与订单类VoucherOrder对应
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0