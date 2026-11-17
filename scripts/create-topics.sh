#!/usr/bin/env bash
# 在 RocketMQ 里建 m4 用到的两个 topic。ROCKETMQ_HOME 指向 RocketMQ 的安装目录。
set -euo pipefail
: "${ROCKETMQ_HOME:?请设置 ROCKETMQ_HOME，比如 export ROCKETMQ_HOME=~/apps/rocketmq-5.5.1}"
for topic in seckill-order order-timeout; do
  sh "$ROCKETMQ_HOME/bin/mqadmin" updateTopic -n "${NAMESRV:-127.0.0.1:9876}" -c DefaultCluster -t $topic -r 4 -w 4
done
