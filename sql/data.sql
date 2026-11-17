-- 初始数据：10000 个用户、10 家商户、1 张秒杀券 (库存 100)。可以重复执行。
USE flashsale;
SET SESSION cte_max_recursion_depth = 10000;
DELETE FROM t_voucher_order;
DELETE FROM t_voucher_order_naive;
DELETE FROM t_voucher;
DELETE FROM t_shop;
DELETE FROM t_user;

INSERT INTO t_user (id, phone, nickname)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000)
SELECT n, CONCAT('138', LPAD(n, 8, '0')), CONCAT('用户', n) FROM seq;

INSERT INTO t_shop (id, name, address, avg_price, score)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10)
SELECT n, CONCAT('第', n, '家火锅'), CONCAT('幸福路 ', n, ' 号'), 8000 + n * 100, 40 + n % 10 FROM seq;

INSERT INTO t_voucher (id, shop_id, title, pay_value, stock, begin_time, end_time)
VALUES (1, 1, '100 元代金券', 5000, 100, '2026-01-01 00:00:00', '2030-01-01 00:00:00');
