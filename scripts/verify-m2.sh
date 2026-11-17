#!/usr/bin/env bash
# m2 的对照实验：缓存的收益、缓存穿透、缓存击穿、更新时的缓存一致性。
# 需要本机已启动 MySQL 8.4 和 Redis。实验接口在 /lab 下 (见 ShopLabController)。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA21_HOME:-/opt/homebrew/opt/openjdk@21}
MYSQL=${MYSQL:-mysql}
JAVA="$JAVA_HOME/bin/java"
[ -f target/flashsale-mini.jar ] && [ -z "$(find src pom.xml -newer target/flashsale-mini.jar)" ] || mvn -q -DskipTests package

"$MYSQL" -u root < sql/schema.sql
"$MYSQL" -u root < sql/data.sql
"$JAVA" -jar target/flashsale-mini.jar >/tmp/flashsale-m2.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null' EXIT
for _ in $(seq 1 60); do curl -s -o /dev/null localhost:8080/shop/1 && break; sleep 0.5; done

reset() { curl -s -X POST "localhost:8080/lab/reset?queryDelayMs=$1&txWorkMs=$2" >/dev/null; }
queries() { curl -s localhost:8080/lab/stats | sed 's/.*"data":\([0-9]*\).*/\1/'; }
bench() { "$JAVA" tools/Bench.java get "$@"; }

echo "== 1. 缓存的收益：64 个并发读同一家商户，3 万次请求 (先预热一轮)"
for v in db aside twoLevel; do bench /lab/shop/$v/1 20000 64 >/dev/null; done
for delay in 0 10; do
  reset $delay 0
  echo "-- 模拟查询耗时 ${delay} ms"
  for v in db aside twoLevel; do printf '%-9s' "$v"; bench /lab/shop/$v/1 30000 64 | head -1; done
done

echo
echo "== 2. 缓存穿透：反复查一个不存在的商户 (id 9999)，1000 次请求，并发 50"
for v in aside mutex; do
  reset 0 0
  printf '%-9s' "$v"; bench /lab/shop/$v/9999 1000 50 | tail -1
  echo "         数据库查询 $(queries) 次"
done

echo
echo "== 3. 缓存击穿：热 key 刚过期，500 个请求同时到达 (模拟查询耗时 50 ms)"
for v in aside mutex logical; do
  reset 50 0
  curl -s localhost:8080/lab/shop/$v/1 >/dev/null
  curl -s -X POST localhost:8080/lab/shop/1/expire >/dev/null
  before=$(queries)
  printf '%-9s' "$v"; bench /lab/shop/$v/1 500 500 | head -1
  sleep 0.3   # 等逻辑过期的后台重建完成
  echo "         数据库查询 $(( $(queries) - before )) 次"
done

echo
echo "== 4. 更新时的缓存一致性：8 个线程不停地读，同时改一次价格，100 轮"
for work in 0 20; do
  echo "-- 事务里其他操作耗时 ${work} ms"
  for s in deleteFirst deleteInTx afterCommit; do
    reset 0 $work
    "$JAVA" tools/Consistency.java $s 100 8
  done
done
