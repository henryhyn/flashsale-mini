-- flashsale-mini 的表结构。可以重复执行：会删掉旧表重建。
CREATE DATABASE IF NOT EXISTS flashsale DEFAULT CHARACTER SET utf8mb4;
USE flashsale;

DROP TABLE IF EXISTS t_voucher_order;
DROP TABLE IF EXISTS t_voucher_order_naive;
DROP TABLE IF EXISTS t_voucher;
DROP TABLE IF EXISTS t_shop;
DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone       VARCHAR(11) NOT NULL,
  nickname    VARCHAR(32) NOT NULL,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB COMMENT '用户';

CREATE TABLE t_shop (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL,
  address     VARCHAR(128) NOT NULL,
  avg_price   INT          NOT NULL COMMENT '人均价格，单位分',
  score       INT          NOT NULL COMMENT '评分 × 10',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB COMMENT '商户';

CREATE TABLE t_voucher (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  shop_id    BIGINT      NOT NULL,
  title      VARCHAR(64) NOT NULL,
  pay_value  INT         NOT NULL COMMENT '支付金额，单位分',
  stock      INT         NOT NULL COMMENT '剩余库存',
  begin_time DATETIME    NOT NULL,
  end_time   DATETIME    NOT NULL,
  KEY idx_shop (shop_id)
) ENGINE = InnoDB COMMENT '秒杀优惠券';

CREATE TABLE t_voucher_order (
  id          BIGINT PRIMARY KEY COMMENT '订单号',
  user_id     BIGINT   NOT NULL,
  voucher_id  BIGINT   NOT NULL,
  status      TINYINT  NOT NULL DEFAULT 0 COMMENT '0 待支付 1 已支付 2 已取消',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_voucher (user_id, voucher_id) COMMENT '一人一单的最终兜底'
) ENGINE = InnoDB COMMENT '优惠券订单';

-- 演示“错误写法”用：和 t_voucher_order 一样，但没有 (user_id, voucher_id) 唯一索引，一人一单只靠代码判断
CREATE TABLE t_voucher_order_naive (
  id          BIGINT PRIMARY KEY COMMENT '订单号',
  user_id     BIGINT   NOT NULL,
  voucher_id  BIGINT   NOT NULL,
  status      TINYINT  NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_voucher (user_id, voucher_id)
) ENGINE = InnoDB COMMENT '优惠券订单 (演示用，没有唯一索引)';
