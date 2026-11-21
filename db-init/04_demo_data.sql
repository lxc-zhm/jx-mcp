-- demo 业务表 + 种子数据（全新环境，开箱可验收）
CREATE TABLE IF NOT EXISTS demo_customers (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    city       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO demo_customers (name, city) VALUES
    ('张伟', '北京'),
    ('王芳', '上海'),
    ('李娜', '广州'),
    ('刘强', '深圳'),
    ('陈静', '杭州');

CREATE TABLE IF NOT EXISTS demo_orders (
    id          SERIAL PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES demo_customers (id),
    product     TEXT NOT NULL,
    amount      NUMERIC(10, 2) NOT NULL,
    status      TEXT NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO demo_orders (customer_id, product, amount, status) VALUES
    (1, '机械键盘', 599.00, 'paid'),
    (1, '显示器支架', 329.00, 'pending'),
    (2, '无线鼠标', 199.00, 'paid'),
    (2, 'USB-C 扩展坞', 459.00, 'shipped'),
    (3, '办公椅', 1299.00, 'paid'),
    (3, '升降桌', 2199.00, 'pending'),
    (4, '降噪耳机', 899.00, 'paid'),
    (5, '台灯', 259.00, 'shipped');
