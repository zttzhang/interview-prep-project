-- =====================================================
-- 面试教学工程 - 数据库初始化脚本
-- 用于创建测试所需的表结构和测试数据
-- 数据库：PostgreSQL 15
-- =====================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    password_hash VARCHAR(255) NOT NULL,
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. 订单表
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    product_count INTEGER NOT NULL DEFAULT 1,
    total_amount DECIMAL(12, 2) NOT NULL,
    status INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 秒杀商品表
CREATE TABLE IF NOT EXISTS seckill_products (
    id BIGSERIAL PRIMARY KEY,
    product_name VARCHAR(200) NOT NULL,
    original_price DECIMAL(12, 2) NOT NULL,
    seckill_price DECIMAL(12, 2) NOT NULL,
    stock INTEGER NOT NULL DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. 秒杀订单表
CREATE TABLE IF NOT EXISTS seckill_orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    status INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. 消息消费记录表（用于幂等性测试）
CREATE TABLE IF NOT EXISTS message_consume_records (
    id BIGSERIAL PRIMARY KEY,
    msg_id VARCHAR(64) NOT NULL UNIQUE,
    msg_content TEXT,
    status INTEGER DEFAULT 0,
    retry_count INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 6. 延迟队列测试表
CREATE TABLE IF NOT EXISTS delay_queue_tasks (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE,
    task_type VARCHAR(50) NOT NULL,
    payload TEXT,
    execute_time TIMESTAMP NOT NULL,
    status INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 测试数据
-- =====================================================

-- 插入测试用户
INSERT INTO users (username, email, phone, password_hash, status) VALUES
('zhangsan', 'zhangsan@example.com', '13800138001', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 1),
('lisi', 'lisi@example.com', '13800138002', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 1),
('wangwu', 'wangwu@example.com', '13800138003', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 1),
('zhaoliu', 'zhaoliu@example.com', '13800138004', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 1),
('sunqi', 'sunqi@example.com', '13800138005', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 1);

-- 插入测试订单
INSERT INTO orders (order_no, user_id, product_name, product_count, total_amount, status) VALUES
('ORD202401010001', 1, 'iPhone 15 Pro', 1, 8999.00, 1),
('ORD202401010002', 2, 'MacBook Pro 14', 1, 14999.00, 1),
('ORD202401010003', 3, 'AirPods Pro', 2, 3798.00, 0),
('ORD202401010004', 1, 'iPad Air', 1, 4799.00, 1),
('ORD202401010005', 4, 'Apple Watch', 1, 2999.00, 2);

-- 插入秒杀商品
INSERT INTO seckill_products (product_name, original_price, seckill_price, stock, start_time, end_time) VALUES
('iPhone 15', 5999.00, 4999.00, 100, '2024-01-01 00:00:00', '2025-12-31 23:59:59'),
('茅台飞天53度', 1499.00, 999.00, 50, '2024-01-01 00:00:00', '2025-12-31 23:59:59'),
('戴森吹风机', 2999.00, 1999.00, 30, '2024-01-01 00:00:00', '2025-12-31 23:59:59');

-- 插入延迟队列测试任务
INSERT INTO delay_queue_tasks (task_id, task_type, payload, execute_time, status) VALUES
('TASK001', 'ORDER_TIMEOUT', '{"orderNo":"ORD001"}', CURRENT_TIMESTAMP + INTERVAL '1 minute', 0),
('TASK002', 'ORDER_TIMEOUT', '{"orderNo":"ORD002"}', CURRENT_TIMESTAMP + INTERVAL '5 minutes', 0),
('TASK003', 'REFUND_PROCESS', '{"orderNo":"ORD003","amount":100.00}', CURRENT_TIMESTAMP + INTERVAL '10 minutes', 0);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_order_no ON orders(order_no);
CREATE INDEX IF NOT EXISTS idx_seckill_orders_user_product ON seckill_orders(user_id, product_id);
CREATE INDEX IF NOT EXISTS idx_message_consume_records_msg_id ON message_consume_records(msg_id);
CREATE INDEX IF NOT EXISTS idx_delay_queue_tasks_execute_time ON delay_queue_tasks(execute_time);

-- =====================================================
-- 表和列注释（PostgreSQL 标准语法）
-- =====================================================
COMMENT ON TABLE users IS '用户表';
COMMENT ON TABLE orders IS '订单表';
COMMENT ON TABLE seckill_products IS '秒杀商品表';
COMMENT ON TABLE seckill_orders IS '秒杀订单表';
COMMENT ON TABLE message_consume_records IS '消息消费记录表（用于幂等性测试）';
COMMENT ON TABLE delay_queue_tasks IS '延迟队列任务表';

COMMENT ON COLUMN users.status IS '状态：1-正常 0-禁用';
COMMENT ON COLUMN orders.status IS '状态：0-待支付 1-已支付 2-已取消 3-已退款';
COMMENT ON COLUMN seckill_orders.status IS '状态：0-创建 1-成功 2-失败';
COMMENT ON COLUMN message_consume_records.status IS '状态：0-待处理 1-已处理 2-处理失败';
COMMENT ON COLUMN delay_queue_tasks.status IS '状态：0-待执行 1-执行中 2-已完成 3-已失败';
