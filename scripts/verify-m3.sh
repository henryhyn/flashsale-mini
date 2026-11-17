#!/usr/bin/env bash
# m3 的对照实验：同步版 vs Redis + Lua 版的吞吐；部署两个实例时几种一人一单写法的重复订单数；限流。
# 需要本机已启动 MySQL 8.4 和 Redis。会启动两个实例 (8080、8081)。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA21_HOME:-/opt/homebrew/opt/openjdk@21}
MYSQL=${MYSQL:-mysql}
JAVA="$JAVA_HOME/bin/java"
[ -f target/flashsale-mini.jar ] && [ -z "$(find src pom.xml -newer target/flashsale-mini.jar)" ] || mvn -q -DskipTests package

"$MYSQL" -u root < sql/schema.sql
"$MYSQL" -u root < sql/data.sql
"$JAVA" -jar target/flashsale-mini.jar >/tmp/flashsale-m3-a.log 2>&1 &
A=$!
"$JAVA" -jar target/flashsale-mini.jar --server.port=8081 --flashsale.worker-id=2 >/tmp/flashsale-m3-b.log 2>&1 &
B=$!
trap 'kill $A $B 2>/dev/null' EXIT
for port in 8080 8081; do
  for _ in $(seq 1 60); do curl -s -o /dev/null localhost:$port/shop/1 && break; sleep 0.5; done
done

# 重置数据：库存写进数据库，并预热到 Redis；等 1 秒让各实例的售罄标记失效
prepare() {
  "$MYSQL" -u root < sql/data.sql
  "$MYSQL" -u root -D flashsale -e "UPDATE t_voucher SET stock = $1 WHERE id = 1"
  curl -s -X POST "localhost:8080/lab/seckill/1/preheat?stock=$1" >/dev/null
  sleep 1
}
check() {
  "$MYSQL" -u root -D flashsale --table -e "
    SELECT (SELECT stock FROM t_voucher WHERE id = 1) AS 剩余库存, COUNT(*) AS 订单数,
           COUNT(*) - COUNT(DISTINCT user_id) AS 重复订单
    FROM $1 WHERE voucher_id = 1;"
}

echo "== 1. 同步版 vs Lua 版：2000 个用户每人连点 3 次，抢 100 张券，200 个并发 (单实例，先预热一轮)"
for path in /seckill/sync/1 /seckill/redis/1; do prepare 100; "$JAVA" tools/Bench.java $path 2000 3 200 >/dev/null; done
for v in sync lua; do
  prepare 100
  path=/seckill/sync/1; [ $v = lua ] && path=/seckill/redis/1
  echo "-- $([ $v = sync ] && echo '同步版：数据库条件更新 + 唯一索引' || echo 'Lua 版：Redis 判断资格后再落库')"
  "$JAVA" tools/Bench.java $path 2000 3 200
  check t_voucher_order
done

echo
echo "== 2. 一人一单：500 个用户每人连点 2 次，抢 1000 张券 (库存足够，正确结果是 500 单)"
echo "   订单写进没有唯一索引的演示表，一人一单只靠各自的机制 (先在两个实例上预热一轮：冷启动时请求处理得慢，竞态不容易出现)"
for name in jvmLock redissonLock lua; do
  prepare 1000
  BASE=http://localhost:8080,http://localhost:8081 "$JAVA" tools/Bench.java /seckill/lab/$name/1 500 2 >/dev/null
done
for v in jvmLock:单实例 jvmLock:双实例 redissonLock:双实例 lua:双实例; do
  name=${v%%:*}; mode=${v##*:}
  prepare 1000
  echo "-- ${name}，${mode}"
  if [ $mode = 双实例 ]; then
    BASE=http://localhost:8080,http://localhost:8081 "$JAVA" tools/Bench.java /seckill/lab/$name/1 500 2
  else
    "$JAVA" tools/Bench.java /seckill/lab/$name/1 500 2
  fi
  check t_voucher_order_naive
done

echo
echo "== 3. 限流：令牌桶每秒 1000 个令牌、容量 200，2000 个用户每人连点 3 次，200 个并发 (单实例，Lua 版)"
prepare 100
curl -s -X POST "localhost:8080/lab/seckill/rateLimit?permitsPerSecond=1000&capacity=200" >/dev/null
"$JAVA" tools/Bench.java /seckill/redis/1 2000 3 200
curl -s -X POST "localhost:8080/lab/seckill/rateLimit?permitsPerSecond=0" >/dev/null
check t_voucher_order
