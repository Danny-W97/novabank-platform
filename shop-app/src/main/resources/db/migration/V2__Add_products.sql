ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS old_price  NUMERIC(19, 2);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS category   VARCHAR(32);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS emoji      VARCHAR(10);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS brand      VARCHAR(100);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS tag        VARCHAR(16);
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS stars      INTEGER DEFAULT 5;
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS reviews    INTEGER DEFAULT 0;

ALTER TABLE shop_products DROP CONSTRAINT IF EXISTS ck_shop_products_old_price;
ALTER TABLE shop_products DROP CONSTRAINT IF EXISTS ck_shop_products_stars;
ALTER TABLE shop_products DROP CONSTRAINT IF EXISTS ck_shop_products_tag;

ALTER TABLE shop_products ADD CONSTRAINT ck_shop_products_old_price CHECK (old_price IS NULL OR old_price > 0);
ALTER TABLE shop_products ADD CONSTRAINT ck_shop_products_stars     CHECK (stars IS NULL OR stars BETWEEN 1 AND 5);
ALTER TABLE shop_products ADD CONSTRAINT ck_shop_products_tag       CHECK (tag IS NULL OR tag IN ('new','sale','hit'));


INSERT INTO shop_products (sku, name, description, price, old_price, currency, category, emoji, brand, tag, stars, reviews)
VALUES
    ('NOVATECH-001', 'MacBook Pro M4 16"',
     'Laptop Apple z procesorem M4, 24 GB RAM, dysk SSD 1 TB. Najwydajniejszy Mac w historii.',
     8999.00, 11499.00, 'PLN', 'laptop', '💻', 'Apple', 'sale', 5, 342),

    ('NOVATECH-002', 'Samsung Galaxy S25 Ultra',
     'Flagowy smartfon Samsung z 12 GB RAM, 512 GB, aparatem 200 MP i rysikiem S Pen.',
     5499.00, NULL, 'PLN', 'phone', '📱', 'Samsung', 'new', 5, 218),

    ('NOVATECH-003', 'Sony WH-1000XM6',
     'Słuchawki bezprzewodowe z aktywną redukcją szumów, 40 h działania i Bluetooth 5.3.',
     1299.00, 1599.00, 'PLN', 'audio', '🎧', 'Sony', 'sale', 5, 891),

    ('NOVATECH-004', 'PlayStation 5 Slim',
     'Konsola Sony PS5 Slim z dyskiem 1 TB, rozdzielczością 4K i płynnością 120 fps.',
     2399.00, NULL, 'PLN', 'gaming', '🎮', 'PlayStation', 'hit', 5, 1204),

    ('NOVATECH-005', 'iPhone 16 Pro',
     'Smartfon Apple z chipem A18 Pro, 8 GB RAM, 256 GB pamięci i systemem potrójnych aparatów.',
     5199.00, 5699.00, 'PLN', 'phone', '📱', 'Apple', 'sale', 5, 567),

    ('NOVATECH-006', 'Lenovo ThinkPad X1 Carbon',
     'Ultralekki laptop biznesowy z procesorem Intel Core i7, 32 GB RAM i ekranem 14".',
     6299.00, NULL, 'PLN', 'laptop', '💻', 'Lenovo', 'new', 4, 143),

    ('NOVATECH-007', 'Philips Hue Starter Kit',
     'Zestaw 4 żarówek inteligentnych Philips Hue z mostkiem i aplikacją mobilną.',
     399.00, 549.00, 'PLN', 'smart', '💡', 'Philips', 'sale', 4, 328),

    ('NOVATECH-008', 'Canon EOS R50 Mark II',
     'Bezlusterkowiec Canon z matrycą 24,2 MP, nagrywaniem 4K i łącznością Wi-Fi.',
     3799.00, NULL, 'PLN', 'photo', '📷', 'Canon', 'new', 5, 97),

    ('NOVATECH-009', 'Xbox Series X',
     'Konsola Microsoft Xbox Series X z dyskiem 1 TB, 4K i dostępem do Game Pass.',
     2199.00, 2499.00, 'PLN', 'gaming', '🕹️', 'Microsoft', 'sale', 5, 756),

    ('NOVATECH-010', 'iPad Pro M4 13"',
     'Tablet Apple z wyświetlaczem OLED 13", chipem M4, 8 GB RAM i 256 GB pamięci.',
     6199.00, NULL, 'PLN', 'tablet', '📟', 'Apple', 'new', 5, 201),

    ('NOVATECH-011', 'Bose QuietComfort Ultra',
     'Słuchawki Bose z aktywnym wyciszaniem hałasu, 24 h działania i złączem USB-C.',
     1499.00, 1799.00, 'PLN', 'audio', '🎵', 'Bose', 'hit', 4, 445),

    ('NOVATECH-012', 'Asus ROG Zephyrus G16',
     'Laptop gamingowy Asus ROG z kartą RTX 4070, 32 GB RAM i ekranem 16".',
     7299.00, 8499.00, 'PLN', 'laptop', '🖥️', 'Asus', 'sale', 5, 189)
ON CONFLICT (sku) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    price = EXCLUDED.price,
    old_price = EXCLUDED.old_price,
    currency = EXCLUDED.currency,
    category = EXCLUDED.category,
    emoji = EXCLUDED.emoji,
    brand = EXCLUDED.brand,
    tag = EXCLUDED.tag,
    stars = EXCLUDED.stars,
    reviews = EXCLUDED.reviews;
