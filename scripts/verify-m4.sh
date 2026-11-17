#!/usr/bin/env bash
# m4 的对照实验：异步落库的收益；消费者崩溃、数据库短暂不可用、消息重复投递时的少卖和重复订单，以及对账修复；订单超时取消。
# 需要本机已启动 MySQL 8.4、Redis 和 RocketMQ 5 (NameServer 127.0.0.1:9876，先运行 scripts/create-topics.sh 建 topic)。
# MySQL、redis-cli 不在 PATH 里时用 MYSQL=...、REDIS_CLI=... 指定。约 3 分钟。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA21_HOME:-/opt/homebrew/opt/openjdk@21}
MYSQL=${MYSQL:-mysql}
REDIS_CLI=${REDIS_CLI:-redis-cli}
JAVA="$JAVA_HOME/bin/java"
[ -f target/flashsale-mini.jar ] && [ -z "$(find src pom.xml -newer target/flashsale-mini.jar)" ] || mvn -q -DskipTests package

APP=
start_app() {
  "$JAVA" -jar target/flashsale-mini.jar >>/tmp/flashsale-m4.log 2>&1 &
  APP=$!
  for _ in $(seq 1 90); do curl -s -o /dev/null localhost:8080/shop/1 && return; sleep 0.5; done
  echo "应用没有启动成功，日志见 /tmp/flashsale-m4.log"; exit 1
}
trap 'kill $APP 2>/dev/null' EXIT

sql() { "$MYSQL" -u root -D flashsale -N -e "$1"; }
config() { curl -s -X POST "localhost:8080/lab/order/config?$1" >/dev/null; }
stat() { curl -s localhost:8080/lab/order/stats | sed "s/.*\"$1\":\([0-9]*\).*/\1/"; }
orders() { sql "SELECT COUNT(*) FROM t_voucher_order WHERE voucher_id = 1"; }
reserved() { "$REDIS_CLI" hlen 'seckill:users:{1}'; }
# 等订单数连续 3 秒不变 (消息消费完了)
settle() {
  local last=-1 same=0 n
  while [ $same -lt 6 ]; do
    n=$(orders); if [ "$n" = "$last" ]; then same=$((same + 1)); else same=0; last=$n; fi
    sleep 0.5
  done
}
# 等订单数追上 Redis 里拿到资格的人数 (最多等 $1 秒)，再等它稳定
catch_up() {
  for _ in $(seq 1 $(($1 * 2))); do [ "$(orders)" -ge "$(reserved)" ] && break; sleep 0.5; done
  settle
}
prepare() {
  "$MYSQL" -u root < sql/data.sql
  sql "UPDATE t_voucher SET stock = $1 WHERE id = 1"
  curl -s -X POST "localhost:8080/lab/seckill/1/preheat?stock=$1" >/dev/null
  config ""
  sleep 1
}
report() {
  printf '   Redis 里拿到资格 %s 人，数据库订单 %s 张，重复订单 %s，数据库剩余库存 %s\n' \
    "$(reserved)" "$(orders)" "$(sql 'SELECT COUNT(*) - COUNT(DISTINCT user_id) FROM t_voucher_order')" "$(sql 'SELECT stock FROM t_voucher WHERE id = 1')"
}
repair() {
  echo "   对账补单 $(curl -s -X POST 'localhost:8080/lab/order/reconcile?voucherId=1' | sed 's/.*"data":\([0-9]*\).*/\1/') 张"
  report
}

"$MYSQL" -u root < sql/schema.sql
start_app

echo "== 1. 同步落库 (m3) vs 异步落库 (m4)：2000 个用户每人连点 3 次，抢 1000 张券，200 个并发 (先预热两轮，再交替测 3 轮)"
for _ in 1 2; do for path in /seckill/redis/1 /seckill/1; do prepare 1000; "$JAVA" tools/Bench.java $path 2000 3 200 >/dev/null; settle; done; done
for round in 1 2 3; do
  for path in /seckill/redis/1 /seckill/1; do
    prepare 1000
    printf '%s 第 %s 轮  ' "$([ $path = /seckill/1 ] && echo '异步 (发消息就返回)' || echo '同步 (请求里落库)  ')" $round
    "$JAVA" tools/Bench.java $path 2000 3 200 | head -1
    settle
    [ $round = 3 ] && report
  done
done

echo
echo "== 2. 消费者处理到一半进程崩溃：1000 个用户各抢 1 张，消费者每条消息处理 20 ms，落库约 300 张时 kill -9，然后重启"
for mode in ackFirst correct; do
  prepare 1000
  config "mode=$mode&processDelayMs=20"
  echo "-- $([ $mode = ackFirst ] && echo '先确认后处理 (收到消息交给线程池就确认)' || echo '处理完再确认 (正式写法)')"
  "$JAVA" tools/Bench.java /seckill/1 1000 1 200 | tail -1
  while [ "$(orders)" -lt 300 ]; do sleep 0.05; done
  kill -9 $APP; wait $APP 2>/dev/null || true
  echo "   崩溃时已落库 $(orders) 张，重启……"
  start_app
  if [ $mode = correct ]; then catch_up 60; else sleep 5; settle; fi
  echo "   重启后重新投递的消息里，幂等拦下的重复消息 $(stat duplicates) 条"
  report
  repair
done

echo
echo "== 3. 数据库短暂不可用：500 个用户各抢 1 张，开头 3 秒内落库全部失败 (模拟)"
for mode in noRetry correct; do
  prepare 1000
  config "mode=$mode&failSeconds=3"
  echo "-- $([ $mode = noRetry ] && echo '失败只打日志，照样确认' || echo '失败返回 RECONSUME_LATER，稍后重试 (正式写法)')"
  "$JAVA" tools/Bench.java /seckill/1 500 1 200 | tail -1
  if [ $mode = correct ]; then catch_up 90; else sleep 4; settle; fi
  echo "   落库失败 $(stat failures) 次"
  report
  [ $mode = noRetry ] && repair
done

echo
echo "== 4. 消息重复投递：500 个用户各抢 1 张，每条下单消息发两次"
for mode in notIdempotent correct; do
  prepare 1000
  config "mode=$mode&sendTwice=true"
  echo "-- $([ $mode = notIdempotent ] && echo '不幂等：消费者自己生成订单号，写进没有唯一索引的演示表' || echo '幂等：订单号在发消息前生成，作主键 (正式写法)')"
  "$JAVA" tools/Bench.java /seckill/1 500 1 200 | tail -1
  settle; sleep 2
  if [ $mode = notIdempotent ]; then
    printf '   Redis 里拿到资格 %s 人，演示表订单 %s 张，重复订单 %s，数据库剩余库存 %s\n' "$(reserved)" \
      "$(sql 'SELECT COUNT(*) FROM t_voucher_order_naive')" "$(sql 'SELECT COUNT(*) - COUNT(DISTINCT user_id) FROM t_voucher_order_naive')" "$(sql 'SELECT stock FROM t_voucher WHERE id = 1')"
  else
    echo "   幂等拦下的重复消息 $(stat duplicates) 条"
    report
  fi
done

echo
echo "== 5. 订单超时取消：100 个用户各抢 1 张，前 40 个用户支付，支付超时设为 5 秒"
prepare 100
config "payTimeoutSeconds=5"
"$JAVA" tools/Bench.java /seckill/1 100 1 | tail -1
settle
for i in $(seq 1 40); do   # 前 40 个用户支付
  token=$(curl -s -X POST "localhost:8080/user/login?phone=138$(printf '%08d' $i)" | sed 's/.*"data":"\([^"]*\)".*/\1/')
  order=$(sql "SELECT id FROM t_voucher_order WHERE user_id = $i")
  curl -s -X POST "localhost:8080/order/$order/pay" -H "Authorization: $token" >/dev/null
done
sleep 8
printf '   已支付 %s 张，已取消 %s 张，待支付 %s 张；数据库剩余库存 %s，Redis 剩余库存 %s，Redis 里拿到资格 %s 人\n' \
  "$(sql 'SELECT COUNT(*) FROM t_voucher_order WHERE status = 1')" "$(sql 'SELECT COUNT(*) FROM t_voucher_order WHERE status = 2')" \
  "$(sql 'SELECT COUNT(*) FROM t_voucher_order WHERE status = 0')" "$(sql 'SELECT stock FROM t_voucher WHERE id = 1')" \
  "$("$REDIS_CLI" get 'seckill:stock:{1}')" "$(reserved)"
echo "   被取消的用户重新抢："
"$JAVA" tools/Bench.java /seckill/1 100 1 | tail -1
