-- 下单失败时回滚 Redis 里的预扣：库存加回去；ARGV[2] 为 1 时把用户从集合里移除
-- KEYS[1] 库存，KEYS[2] 已下单的用户集合；ARGV[1] 用户 id
redis.call('incr', KEYS[1])
if ARGV[2] == '1' then redis.call('srem', KEYS[2], ARGV[1]) end
return 0
