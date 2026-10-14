-- ============================================================
-- 虚拟数字资产交易平台 · 完整初始化脚本（MySQL 8.0 / utf8mb4）
-- 仅在数据卷 data/mysql 为空时由容器自动执行一次
-- 导入会话强制 utf8mb4，防止客户端 latin1 协商导致中文 ????
-- ============================================================
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS trade_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE trade_db;

-- ---------------------------- 用户表 ----------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
  `password` VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
  `nickname` VARCHAR(50) COMMENT '昵称',
  `phone` VARCHAR(20) COMMENT '手机号',
  `email` VARCHAR(100) COMMENT '邮箱',
  `avatar` VARCHAR(255) COMMENT '头像相对路径',
  `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色：user/admin',
  `balance` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '可用余额',
  `frozen_balance` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '冻结余额（交易托管资金）',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ---------------------------- 游戏类型表 ----------------------------
CREATE TABLE IF NOT EXISTS `game_type` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '游戏类型ID',
  `type_name` VARCHAR(50) NOT NULL COMMENT '游戏类型名称',
  `icon` VARCHAR(20) DEFAULT NULL COMMENT '分类图标(emoji)',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序，数字越小越靠前',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏类型表';

-- ---------------------------- 商品表 ----------------------------
CREATE TABLE IF NOT EXISTS `product` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '商品ID',
  `seller_id` INT NOT NULL COMMENT '卖家ID',
  `game_type_id` INT COMMENT '游戏类型ID',
  `game_type` VARCHAR(50) COMMENT '游戏类型名称',
  `title` VARCHAR(200) NOT NULL COMMENT '商品标题',
  `game_server` VARCHAR(50) COMMENT '区服',
  `role_level` INT DEFAULT 0 COMMENT '角色等级',
  `price` DECIMAL(10,2) NOT NULL COMMENT '价格',
  `description` TEXT COMMENT '商品描述',
  `detail_desc` TEXT COMMENT '商品详情描述（富文本）',
  `detail_images` TEXT COMMENT '商品详情图片（JSON数组）',
  `image_url` VARCHAR(255) COMMENT '商品主图相对路径',
  `game_account` VARCHAR(100) COMMENT '游戏账号（发货后买家可见）',
  `game_password` VARCHAR(100) COMMENT '游戏密码',
  `bind_info` VARCHAR(200) COMMENT '绑定信息',
  `status` INT DEFAULT 0 COMMENT '状态：0待审核 1已上架 2拒绝 3下架',
  `reject_reason` VARCHAR(500) COMMENT '拒绝原因',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  FOREIGN KEY (`seller_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ---------------------------- 订单表 ----------------------------
CREATE TABLE IF NOT EXISTS `order` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
  `order_no` VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
  `product_id` INT NOT NULL COMMENT '商品ID',
  `buyer_id` INT NOT NULL COMMENT '买家ID',
  `seller_id` INT NOT NULL COMMENT '卖家ID',
  `price` DECIMAL(10,2) NOT NULL COMMENT '成交价格',
  `status` INT DEFAULT 1 COMMENT '状态：1待付款 2已付款 3已发货 4已完成 5已取消',
  `account_info` TEXT COMMENT '账号信息（卖家发货时填写）',
  `payment_method` VARCHAR(20) COMMENT '支付方式：balance/alipay',
  `trade_no` VARCHAR(100) COMMENT '支付宝交易号',
  `pay_time` DATETIME COMMENT '支付时间',
  `delivery_time` DATETIME COMMENT '发货时间',
  `received_time` DATETIME COMMENT '确认收货时间',
  `auto_confirm_deadline` DATETIME COMMENT '自动确认收货截止时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  FOREIGN KEY (`product_id`) REFERENCES `product`(`id`) ON DELETE CASCADE,
  FOREIGN KEY (`buyer_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  FOREIGN KEY (`seller_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ---------------------------- 交易流水表（旧） ----------------------------
CREATE TABLE IF NOT EXISTS `transaction` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '流水ID',
  `order_no` VARCHAR(50) COMMENT '订单号',
  `buyer_id` INT COMMENT '买家ID',
  `seller_id` INT COMMENT '卖家ID',
  `user_id` INT COMMENT '操作用户ID',
  `money` DECIMAL(10,2) NOT NULL COMMENT '金额',
  `type` INT COMMENT '类型：1收入 2支出',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  FOREIGN KEY (`buyer_id`) REFERENCES `user`(`id`) ON DELETE SET NULL,
  FOREIGN KEY (`seller_id`) REFERENCES `user`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易流水表（旧）';

-- ---------------------------- 账户变动明细表（新，余额/冻结/充值/收支） ----------------------------
CREATE TABLE IF NOT EXISTS `transaction_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '明细ID',
  `user_id` INT NOT NULL COMMENT '用户ID',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '变动金额（正为增加，负为减少）',
  `type` VARCHAR(20) NOT NULL COMMENT '类型：recharge/pay/freeze/income/refund',
  `order_id` BIGINT COMMENT '关联订单ID（交易订单或充值订单）',
  `balance_before` DECIMAL(12,2) COMMENT '变动前余额',
  `balance_after` DECIMAL(12,2) COMMENT '变动后余额',
  `remark` VARCHAR(255) COMMENT '备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户变动明细表';

-- ---------------------------- 充值订单表 ----------------------------
CREATE TABLE IF NOT EXISTS `recharge_order` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '充值订单ID',
  `order_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '充值订单号',
  `user_id` INT NOT NULL COMMENT '用户ID',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '充值金额',
  `status` INT NOT NULL DEFAULT 0 COMMENT '0待支付 1支付成功 2已取消',
  `payment_method` VARCHAR(20) COMMENT '支付方式：alipay/balance',
  `trade_no` VARCHAR(100) COMMENT '支付宝交易号',
  `pay_time` DATETIME COMMENT '支付时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单表';

-- ---------------------------- 消息通知表 ----------------------------
CREATE TABLE IF NOT EXISTS `notification` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
  `user_id` INT COMMENT '接收用户ID（NULL表示广播）',
  `type` VARCHAR(20) NOT NULL COMMENT '消息类型：order/feedback/broadcast/system',
  `title` VARCHAR(100) NOT NULL COMMENT '消息标题',
  `content` TEXT NOT NULL COMMENT '消息内容',
  `order_id` INT COMMENT '关联订单ID',
  `status` INT DEFAULT 0 COMMENT '状态：0未读 1已读（广播已读以notification_read回执为准）',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  FOREIGN KEY (`order_id`) REFERENCES `order`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通知表';

-- ---------------------------- 消息已读回执表（按用户独立已读） ----------------------------
CREATE TABLE IF NOT EXISTS `notification_read` (
  `user_id` INT NOT NULL COMMENT '用户ID',
  `notification_id` INT NOT NULL COMMENT '消息ID（含广播）',
  `read_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '阅读时间',
  PRIMARY KEY (`user_id`, `notification_id`),
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  FOREIGN KEY (`notification_id`) REFERENCES `notification`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息已读记录表';

-- ---------------------------- 用户反馈表 ----------------------------
CREATE TABLE IF NOT EXISTS `feedback` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '反馈ID',
  `user_id` INT NOT NULL COMMENT '用户ID',
  `title` VARCHAR(100) NOT NULL COMMENT '反馈标题',
  `content` TEXT NOT NULL COMMENT '反馈内容',
  `status` INT DEFAULT 0 COMMENT '状态：0待处理 1已回复 2已关闭',
  `reply` TEXT COMMENT '管理员回复',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `reply_time` DATETIME COMMENT '回复时间',
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户反馈表';

-- ---------------------------- 桌宠皮肤表 ----------------------------
CREATE TABLE IF NOT EXISTS `pet_skin` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '皮肤ID',
  `skin_name` VARCHAR(50) NOT NULL COMMENT '皮肤名称',
  `idle_ani` VARCHAR(255) NOT NULL COMMENT '待机动态图地址',
  `click_ani` VARCHAR(255) COMMENT '点击动图地址',
  `main_img` VARCHAR(255) NOT NULL COMMENT '皮肤缩略预览图',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认：1是 0否',
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0下架',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桌宠皮肤表';

-- ---------------------------- 用户桌宠配置表 ----------------------------
CREATE TABLE IF NOT EXISTS `user_pet` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
  `user_id` INT NOT NULL COMMENT '用户ID',
  `skin_id` INT NOT NULL COMMENT '皮肤ID',
  `pos_x` INT DEFAULT 1300 COMMENT '上次拖拽X坐标',
  `pos_y` INT DEFAULT 650 COMMENT '上次拖拽Y坐标',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY `uk_user_pet_uid` (`user_id`),
  FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  FOREIGN KEY (`skin_id`) REFERENCES `pet_skin`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户桌宠配置表';

-- ============================================================
-- 初始数据
-- ============================================================

-- 演示账号：admin / user1 / user2，密码均为 123456（BCrypt 哈希）
INSERT INTO `user` (`username`, `password`, `nickname`, `phone`, `role`, `balance`, `frozen_balance`) VALUES
('admin', '$2a$10$0IpZkqjuwv1ewYIgtLQ9w.2fC9yKWTE6zc5yl9IR5HSuW1G.HfFM6', '平台管理员', '13800138000', 'admin', 1000.00, 0.00),
('user1', '$2a$10$0IpZkqjuwv1ewYIgtLQ9w.2fC9yKWTE6zc5yl9IR5HSuW1G.HfFM6', '买家小明', '13800138001', 'user', 1000.00, 0.00),
('user2', '$2a$10$0IpZkqjuwv1ewYIgtLQ9w.2fC9yKWTE6zc5yl9IR5HSuW1G.HfFM6', '卖家小红', '13800138002', 'user', 1000.00, 0.00)
ON DUPLICATE KEY UPDATE `username`=VALUES(`username`);

-- 游戏分类
INSERT INTO `game_type` (`type_name`, `icon`, `sort_order`, `status`) VALUES
('王者荣耀', '👑', 1, 1),
('英雄联盟', '⚔️', 2, 1),
('和平精英', '🎯', 3, 1),
('我的世界', '⛏️', 4, 1),
('原神', '✨', 5, 1),
('崩坏', '💥', 6, 1)
ON DUPLICATE KEY UPDATE `type_name`=VALUES(`type_name`);

-- 默认桌宠皮肤
INSERT INTO `pet_skin` (`skin_name`, `idle_ani`, `click_ani`, `main_img`, `is_default`, `status`) VALUES
('像素少女', '/images/pet/pixel-girl-idle.svg', '/images/pet/pixel-girl-click.svg', '/images/pet/pixel-girl-preview.png', 1, 1)
ON DUPLICATE KEY UPDATE `skin_name`=VALUES(`skin_name`);
