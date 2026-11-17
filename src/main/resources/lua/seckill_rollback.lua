-- 把预扣的库存还回去 (落库失败、消息发送失败、订单超时取消)；ARGV[2] 为 1 时把用户从 Hash 里移除，允许他再买
-- KEYS[1] 库存，KEYS[2] 已下单的用户；ARGV[1] 用户 id
redis.call('incr', KEYS[1])
if ARGV[2] == '1' then redis.call('hdel', KEYS[2], ARGV[1]) end
return 0
