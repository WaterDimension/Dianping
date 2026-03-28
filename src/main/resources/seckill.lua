-- 1.参数列表
--    优惠券id
local voucherId = ARGV[1]
--    用户id
local userId = ARGV[2]
----    订单id
--local orderId = ARGV[3]

-- 2数据key
--  库存key
local stockKey = 'seckill:stock:' .. voucherId
--  订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
    -- 3.1判断库存是否充足
if (tonumber((reids.call('get', stockKey)) <= 0)) then
    -- 库存不足
    return 1
end
    -- 3.2判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 存在说明下单过了
    return 2
end
    -- 3.3扣库存
redis.call('incrby', stockKey, -1)
--   3.4下单
redis.call('sadd', orderKey, userId)

----  3.5有资格， 发送消息到队列中  xadd stream.orders * k1 v1 k2 v2 ...
--redis.call('xadd', 'stream.orders', "*", userId, )
return 0