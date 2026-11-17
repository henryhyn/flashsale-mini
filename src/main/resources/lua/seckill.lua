-- 秒杀资格判断：校验库存、一人一单、预扣库存、记下用户，四步在 Redis 里原子地完成。
-- KEYS[1] 库存，KEYS[2] 已下单的用户集合；ARGV[1] 用户 id
-- 返回 0 成功，1 已抢光，2 重复下单，3 活动还没有预热
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil then return 3 end
if stock <= 0 then return 1 end
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[1])
return 0
