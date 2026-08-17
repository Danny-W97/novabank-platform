INSERT INTO shop_products (sku, name, description, price, old_price, currency, category, emoji, brand, tag, stars, reviews)
VALUES
    ('NOVATECH-101', 'Mysz bezprzewodowa NovaTech Glide M1',
     'Cicha mysz bezprzewodowa do pracy i nauki, z precyzyjnym sensorem oraz wygodnym profilem.',
     199.00, 249.00, 'PLN', 'mice', 'MYSZ', 'NovaTech', 'sale', 5, 87),

    ('NOVATECH-102', 'Mysz ergonomiczna NovaTech Ergo W2',
     'Ergonomiczna mysz do wielogodzinnej pracy, z regulowaną czułością i akumulatorem USB-C.',
     349.00, NULL, 'PLN', 'mice', 'MYSZ', 'NovaTech', 'new', 5, 42),

    ('NOVATECH-103', 'Mysz gamingowa NovaTech Pulse G3',
     'Lekka mysz gamingowa z szybkim sensorem, programowalnymi przyciskami i subtelnym podświetleniem.',
     449.00, 499.00, 'PLN', 'mice', 'MYSZ', 'NovaTech', 'hit', 5, 126),

    ('NOVATECH-104', 'Klawiatura mechaniczna NovaTech Key 75',
     'Kompaktowa klawiatura mechaniczna 75% z cichymi przełącznikami i aluminiową obudową.',
     399.00, 449.00, 'PLN', 'keyboard', 'KLA', 'NovaTech', 'sale', 5, 64),

    ('NOVATECH-105', 'Klawiatura bezprzewodowa NovaTech Type S',
     'Niskoprofilowa klawiatura bezprzewodowa do biura, z podświetleniem i wielourządzeniowym Bluetooth.',
     449.00, NULL, 'PLN', 'keyboard', 'KLA', 'NovaTech', 'new', 4, 31),

    ('NOVATECH-106', 'Klawiatura gamingowa NovaTech Strike K',
     'Wytrzymała klawiatura gamingowa z mechanicznymi przełącznikami, anti-ghostingiem i trybem gry.',
     229.00, 299.00, 'PLN', 'keyboard', 'KLA', 'NovaTech', 'sale', 5, 113),

    ('NOVATECH-107', 'Monitor NovaTech View 24F',
     'Monitor 24 cale Full HD z cienkimi ramkami, trybem ochrony wzroku i regulacją pochylenia.',
     499.00, 579.00, 'PLN', 'monitor', 'MON', 'NovaTech', 'sale', 4, 76),

    ('NOVATECH-108', 'Monitor NovaTech View 27Q',
     'Monitor 27 cali z wysoką rozdzielczością, matową matrycą i wygodnym stojakiem.',
     699.00, NULL, 'PLN', 'monitor', 'MON', 'NovaTech', 'new', 5, 58),

    ('NOVATECH-109', 'Monitor gamingowy NovaTech Play 24G',
     'Monitor 24 cale do gier z szybkim odświeżaniem, niskim czasem reakcji i synchronizacją obrazu.',
     599.00, 699.00, 'PLN', 'monitor', 'MON', 'NovaTech', 'hit', 5, 144),

    ('NOVATECH-110', 'Kabel HDMI 2 m pozłacany 4K',
     'Wzmocniony, pleciony kabel HDMI 2 m z pozłacanymi końcówkami do obrazu 4K i dźwięku wielokanałowego.',
     180.00, NULL, 'PLN', 'cable', 'HDMI', 'NovaTech', 'hit', 5, 92),

    ('NOVATECH-111', 'Kabel USB-C 1 m nylon',
     'Elastyczny kabel USB-C w oplocie nylonowym do ładowania i synchronizacji urządzeń.',
     49.00, NULL, 'PLN', 'cable', 'USB', 'NovaTech', 'new', 4, 38),

    ('NOVATECH-112', 'Kabel DisplayPort 2 m 8K',
     'Kabel DisplayPort 2 m do monitorów wysokiej rozdzielczości, z solidnymi wtykami i elastycznym oplotem.',
     129.00, 159.00, 'PLN', 'cable', 'DP', 'NovaTech', 'sale', 5, 47),

    ('NOVATECH-113', 'Ekspres automatyczny NovaCafe Aroma 300',
     'Automatyczny ekspres do kawy z młynkiem, spienianiem mleka i programami espresso oraz lungo.',
     1699.00, 1999.00, 'PLN', 'coffee', 'KAWA', 'NovaCafe', 'sale', 5, 81),

    ('NOVATECH-114', 'Ekspres automatyczny NovaCafe Barista 500',
     'Wygodny ekspres automatyczny z ceramicznym młynkiem, profilem użytkownika i karafką mleczną.',
     2999.00, NULL, 'PLN', 'coffee', 'KAWA', 'NovaCafe', 'new', 5, 53),

    ('NOVATECH-115', 'Ekspres kolbowy NovaCafe Pro 800',
     'Stalowy ekspres kolbowy dla miłośników kawy, z profesjonalną dyszą pary i stabilną temperaturą.',
     4999.00, 5499.00, 'PLN', 'coffee', 'KAWA', 'NovaCafe', 'hit', 5, 29)
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
