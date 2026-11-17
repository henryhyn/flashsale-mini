# flashsale-mini

一个本地生活团购场景的秒杀服务，是《Java 后端开发工程师求职全攻略 (27/28 届)》的配套项目。

它和常见的教程项目做的是同一类业务，但只做两条链路 (商户缓存、秒杀下单)，把**异常流程和一致性**做完整：并发下会不会超卖、消息丢了怎么办、重复消费会不会多下单、Redis 和数据库对不上怎么发现和修复。每个里程碑都有一个可以一条命令复现的对照实验，留下前后对比的数据。

## 里程碑

每个里程碑对应一个 Git 标签，`git checkout m1` 就能看到当时的完整代码，每个标签都能单独运行。

| 标签 | 内容 | 对照实验 |
| - | - | - |
| `m1` | 单体起步：登录 (token 存 Redis，拦截器 + ThreadLocal)、统一返回与全局异常、同步版秒杀的错误写法和正确写法 | 500 个用户每人连点 2 次，抢 100 张券：超卖数、重复订单数 |

后续里程碑完成后会追加到这张表里。

## 运行要求

- JDK 21 及以上，Maven 3.9 及以上
- MySQL 8.4 (8.0 也可以)，本机 3306 端口，root 用户无密码 (可以在 `src/main/resources/application.yml` 里修改)
- Redis 7 及以上，本机 6379 端口

## 快速开始

```bash
mysql -u root < sql/schema.sql      # 建库建表
mysql -u root < sql/data.sql        # 1000 个用户 (手机号 13800000001 起)、10 家商户、1 张库存 100 的秒杀券
mvn -DskipTests package
java -jar target/flashsale-mini.jar
```

另开一个终端：

```bash
TOKEN=$(curl -s -X POST 'localhost:8080/user/login?phone=13800000001' | sed 's/.*"data":"\([^"]*\)".*/\1/')
curl -s -X POST localhost:8080/seckill/1 -H "Authorization: $TOKEN"
# {"code":0,"msg":"ok","data":"<订单号>"}
curl -s -X POST localhost:8080/seckill/1 -H "Authorization: $TOKEN"
# {"code":1002,"msg":"不能重复下单","data":null}
```

## m1：同步版秒杀

### 做了什么

- **登录**：按手机号登录 (为了把篇幅留给秒杀，省掉了短信验证码)，生成一个随机 token 存进 Redis，30 分钟过期。登录态放在 Redis 而不是某一台机器的 `HttpSession` 里，以后部署多个实例也能共享。
- **拦截器 + ThreadLocal**：`LoginInterceptor` 从 `Authorization` 头取 token，查出用户 id 放进 `UserHolder` (一个 ThreadLocal)；`afterCompletion` 里一定会 `remove()`，否则线程被下一个请求复用时会读到上一个用户。
- **统一返回与全局异常**：所有接口返回 `{code, msg, data}`；`BizException` 表示可以预期的业务失败 (已抢光、重复下单)，其他异常记日志、给用户一个笼统的提示。
- **订单号**：雪花算法生成，不用数据库自增 (以后分库分表时不会冲突)。
- **两版秒杀** (`SeckillService`)：
  - `naive` (错误写法，接口 `/seckill/naive/{id}`)：先查库存、查这个用户买过没有，在 Java 里判断，再把算好的库存写回、插入订单。订单写进一张没有唯一索引的演示表 `t_voucher_order_naive`；
  - `safe` (正确写法，接口 `/seckill/{id}`)：`UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0` 按影响行数判断是否扣成功；`(user_id, voucher_id)` 唯一索引兜底一人一单，重复下单时整个事务回滚，刚扣的库存也恢复。

### 对照实验

```bash
bash scripts/verify-m1.sh    # 需要先启动 MySQL 和 Redis；MySQL 客户端不在 PATH 里时用 MYSQL=/path/to/mysql 指定
```

500 个用户，每人同时发 2 个请求 (模拟连点)，抢 100 张券。每次实验前重置数据，实验后查库核对。某次运行的结果 (完整输出见 [results/m1.txt](results/m1.txt))：

| 写法 | 订单数 | 下单用户数 | 重复订单 | 超卖 | 剩余库存 | 吞吐 (次/秒) | P99 (ms) |
| - | - | - | - | - | - | - | - |
| 先查后写 (naive) | 645 | 500 | 145 | 545 | 27 | 909 | 1071 |
| 条件更新 + 唯一索引 (safe) | 100 | 100 | 0 | 0 | 0 | 1502 | 628 |

注：Apple M1 Pro，JDK 21.0.10，MySQL 8.4.8，Redis 8.6.2，应用和压测客户端在同一台机器上。每次运行的具体数字不同，但错误写法每次都会超卖几百单、重复下单上百单，正确写法每次都是恰好 100 单。

几个值得看的现象：

- 错误写法卖出了 600 多单，库存却还剩二十几：并发的请求读到同一个库存，各自减一后写回，互相覆盖 (更新丢失)；
- 错误写法里，同一个用户的两个请求同时查到“没买过”，于是都下了单；
- 正确写法不仅数据对，吞吐还更高：它不用先查一遍，一条 UPDATE 就完成了判断和扣减。
- 两种写法的 P99 都在几百毫秒：所有请求都要排队拿数据库连接 (连接池默认 10 个)，并且都在争同一行的锁。这是 m3 把库存预扣挪到 Redis 的原因。

### 简历可以这样写

- 秒杀下单用条件更新 (`stock > 0`) 扣减库存、按影响行数判断结果，`(user_id, voucher_id)` 唯一索引兜底一人一单；压测 500 个用户各连点 2 次抢 100 张券，从“先查后写”的超卖 500 多单降到 0。

### 面试官可能会问

- 先查库存再扣减为什么会超卖？库存为什么会“越卖越多”？
- 条件更新为什么能防超卖？它和 `SELECT ... FOR UPDATE` 有什么区别？
- 一人一单为什么不能只靠代码判断？唯一索引冲突后，已经扣掉的库存怎么办？
- 登录的 token 为什么放 Redis？ThreadLocal 里的用户什么时候清理？不清理会怎样？
- 订单号为什么不用自增 id？

### 扩展方向

- 加上短信验证码登录 (验证码存 Redis，限制发送频率)；
- 用 `SELECT ... FOR UPDATE` 实现第三版，和条件更新比较吞吐；
- 把连接池调大或调小，观察吞吐和 P99 的变化，找到这台机器上的拐点。

## 目录结构

```
sql/            建表和初始数据
src/main/java/flashsale/
  common/       统一返回、全局异常、登录拦截器、UserHolder、订单号生成
  user/         登录
  voucher/      秒杀
tools/Bench.java    压测客户端 (单文件，java tools/Bench.java 直接运行)
scripts/        每个里程碑的对照实验脚本
results/        对照实验的输出
```

## 许可证

[MIT](LICENSE)
