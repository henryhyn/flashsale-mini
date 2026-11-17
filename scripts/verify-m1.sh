#!/usr/bin/env bash
# m1 的对照实验：500 个用户、每人同时发 2 个请求，抢 100 张券，分别打错误写法和正确写法，
# 每次实验前重置数据，实验后查库核对。需要本机已启动 MySQL 8.4 和 Redis。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA21_HOME:-/opt/homebrew/opt/openjdk@21}
MYSQL=${MYSQL:-mysql}
JAVA="$JAVA_HOME/bin/java"
[ -f target/flashsale-mini.jar ] && [ -z "$(find src pom.xml -newer target/flashsale-mini.jar)" ] || mvn -q -DskipTests package

"$MYSQL" -u root < sql/schema.sql
"$JAVA" -jar target/flashsale-mini.jar >/tmp/flashsale-m1.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null' EXIT
for _ in $(seq 1 60); do curl -s -o /dev/null localhost:8080/user/login && break; sleep 0.5; done

for mode in naive safe; do
  "$MYSQL" -u root < sql/data.sql
  path=/seckill/1; table=t_voucher_order
  if [ "$mode" = naive ]; then path=/seckill/naive/1; table=t_voucher_order_naive; fi
  echo "== $([ "$mode" = naive ] && echo '错误写法：先查后写' || echo '正确写法：条件更新 + 唯一索引')"
  "$JAVA" tools/Bench.java "$path" 500 2
  "$MYSQL" -u root -D flashsale --table -e "
    SELECT (SELECT stock FROM t_voucher WHERE id = 1) AS 剩余库存,
           COUNT(*) AS 订单数,
           COUNT(DISTINCT user_id) AS 下单用户数,
           COUNT(*) - COUNT(DISTINCT user_id) AS 重复订单,
           GREATEST(COUNT(*) - 100, 0) AS 超卖
    FROM $table WHERE voucher_id = 1;"
done
