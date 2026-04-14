# Online Flash Sale — Low Level Design (LLD)

## 1. Database Schema Design

### ER Diagram

```mermaid
erDiagram
    USERS {
        bigint id PK
        varchar username UK
        varchar email UK
        varchar password_hash
        varchar phone
        enum role "ADMIN, CUSTOMER"
        timestamp created_at
        timestamp updated_at
    }

    PRODUCTS {
        bigint id PK
        varchar name
        text description
        decimal original_price
        varchar image_url
        varchar category
        boolean is_active
        timestamp created_at
        timestamp updated_at
    }

    FLASH_SALES {
        bigint id PK
        bigint product_id FK
        decimal sale_price
        integer total_quantity
        integer max_per_user
        timestamp start_time
        timestamp end_time
        enum status "UPCOMING, ACTIVE, ENDED, CANCELLED"
        integer version "optimistic lock"
        timestamp created_at
        timestamp updated_at
    }

    INVENTORY {
        bigint id PK
        bigint flash_sale_id FK UK
        integer available_quantity
        integer reserved_quantity
        integer sold_quantity
        integer version "optimistic lock"
        timestamp updated_at
    }

    ORDERS {
        bigint id PK
        varchar order_number UK
        bigint user_id FK
        bigint flash_sale_id FK
        integer quantity
        decimal unit_price
        decimal total_amount
        enum status "CREATED, PAYMENT_PENDING, PAID, CONFIRMED, CANCELLED, REFUNDED"
        varchar idempotency_key UK
        integer version "optimistic lock"
        timestamp created_at
        timestamp updated_at
    }

    PAYMENTS {
        bigint id PK
        bigint order_id FK
        varchar transaction_id UK
        decimal amount
        enum status "PENDING, SUCCESS, FAILED, REFUNDED"
        varchar payment_method
        varchar gateway_response
        timestamp created_at
        timestamp updated_at
    }

    INVENTORY_LOGS {
        bigint id PK
        bigint flash_sale_id FK
        bigint order_id FK
        enum operation "RESERVE, CONFIRM, RELEASE, RECONCILE"
        integer quantity_change
        integer quantity_before
        integer quantity_after
        timestamp created_at
    }

    USERS ||--o{ ORDERS : places
    PRODUCTS ||--o{ FLASH_SALES : "sold in"
    FLASH_SALES ||--|| INVENTORY : has
    FLASH_SALES ||--o{ ORDERS : "purchased through"
    ORDERS ||--o| PAYMENTS : "paid via"
    FLASH_SALES ||--o{ INVENTORY_LOGS : tracks
    ORDERS ||--o{ INVENTORY_LOGS : references
```

---

## 2. DDL — SQL Table Definitions

```sql
-- =============================================
-- USERS TABLE
-- =============================================
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    email           VARCHAR(100) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_email ON users(email);

-- =============================================
-- PRODUCTS TABLE
-- =============================================
CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    original_price  DECIMAL(12,2) NOT NULL,
    image_url       VARCHAR(500),
    category        VARCHAR(100),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- =============================================
-- FLASH SALES TABLE
-- =============================================
CREATE TABLE flash_sales (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT NOT NULL REFERENCES products(id),
    sale_price      DECIMAL(12,2) NOT NULL,
    total_quantity   INTEGER NOT NULL,
    max_per_user    INTEGER NOT NULL DEFAULT 1,
    start_time      TIMESTAMP NOT NULL,
    end_time        TIMESTAMP NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sale_time CHECK (end_time > start_time),
    CONSTRAINT chk_sale_price CHECK (sale_price > 0),
    CONSTRAINT chk_total_qty CHECK (total_quantity > 0)
);
CREATE INDEX idx_flash_sales_status ON flash_sales(status);
CREATE INDEX idx_flash_sales_start ON flash_sales(start_time);

-- =============================================
-- INVENTORY TABLE (Hot path — most contested)
-- =============================================
CREATE TABLE inventory (
    id                  BIGSERIAL PRIMARY KEY,
    flash_sale_id       BIGINT NOT NULL UNIQUE REFERENCES flash_sales(id),
    available_quantity  INTEGER NOT NULL DEFAULT 0,
    reserved_quantity   INTEGER NOT NULL DEFAULT 0,
    sold_quantity       INTEGER NOT NULL DEFAULT 0,
    version             INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_available CHECK (available_quantity >= 0),
    CONSTRAINT chk_reserved CHECK (reserved_quantity >= 0)
);

-- =============================================
-- ORDERS TABLE
-- =============================================
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(50) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    flash_sale_id   BIGINT NOT NULL REFERENCES flash_sales(id),
    quantity        INTEGER NOT NULL DEFAULT 1,
    unit_price      DECIMAL(12,2) NOT NULL,
    total_amount    DECIMAL(12,2) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_sale UNIQUE (user_id, flash_sale_id)
);
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_sale ON orders(flash_sale_id);
CREATE INDEX idx_orders_status ON orders(status);

-- =============================================
-- PAYMENTS TABLE
-- =============================================
CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id),
    transaction_id  VARCHAR(100) UNIQUE,
    amount          DECIMAL(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method  VARCHAR(50),
    gateway_response TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_order ON payments(order_id);

-- =============================================
-- INVENTORY AUDIT LOG
-- =============================================
CREATE TABLE inventory_logs (
    id              BIGSERIAL PRIMARY KEY,
    flash_sale_id   BIGINT NOT NULL REFERENCES flash_sales(id),
    order_id        BIGINT REFERENCES orders(id),
    operation       VARCHAR(20) NOT NULL,
    quantity_change  INTEGER NOT NULL,
    quantity_before  INTEGER NOT NULL,
    quantity_after   INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_inv_log_sale ON inventory_logs(flash_sale_id);
```

---

## 3. Class Diagrams

### 3.1 Core Domain Model

```mermaid
classDiagram
    class FlashSale {
        -Long id
        -Long productId
        -BigDecimal salePrice
        -int totalQuantity
        -int maxPerUser
        -LocalDateTime startTime
        -LocalDateTime endTime
        -SaleStatus status
        -int version
        +isActive() boolean
        +isUpcoming() boolean
        +hasEnded() boolean
    }

    class Inventory {
        -Long id
        -Long flashSaleId
        -int availableQuantity
        -int reservedQuantity
        -int soldQuantity
        -int version
        +canReserve(int qty) boolean
        +reserve(int qty) void
        +confirmSale(int qty) void
        +releaseReservation(int qty) void
    }

    class Order {
        -Long id
        -String orderNumber
        -Long userId
        -Long flashSaleId
        -int quantity
        -BigDecimal unitPrice
        -BigDecimal totalAmount
        -OrderStatus status
        -String idempotencyKey
        -int version
        +isPending() boolean
        +canCancel() boolean
    }

    class Payment {
        -Long id
        -Long orderId
        -String transactionId
        -BigDecimal amount
        -PaymentStatus status
        -String paymentMethod
    }

    class InventoryLog {
        -Long id
        -Long flashSaleId
        -Long orderId
        -InventoryOperation operation
        -int quantityChange
        -int quantityBefore
        -int quantityAfter
    }

    class SaleStatus {
        <<enumeration>>
        UPCOMING
        ACTIVE
        ENDED
        CANCELLED
    }

    class OrderStatus {
        <<enumeration>>
        CREATED
        PAYMENT_PENDING
        PAID
        CONFIRMED
        CANCELLED
        REFUNDED
    }

    FlashSale "1" --> "1" Inventory
    FlashSale "1" --> "*" Order
    Order "1" --> "0..1" Payment
    FlashSale "1" --> "*" InventoryLog
    FlashSale --> SaleStatus
    Order --> OrderStatus
```

### 3.2 Service Layer Architecture

```mermaid
classDiagram
    class FlashSaleService {
        <<interface>>
        +createSale(CreateSaleRequest) FlashSaleDTO
        +getSale(Long id) FlashSaleDTO
        +getActiveSales() List~FlashSaleDTO~
        +activateSale(Long id) void
        +endSale(Long id) void
    }

    class InventoryService {
        <<interface>>
        +reserveStock(Long saleId, Long userId, int qty) ReservationResult
        +confirmReservation(Long saleId, Long orderId) void
        +releaseReservation(Long saleId, Long orderId, int qty) void
        +getStock(Long saleId) int
    }

    class OrderService {
        <<interface>>
        +createOrder(CreateOrderRequest) OrderDTO
        +getOrder(Long id) OrderDTO
        +getUserOrders(Long userId) List~OrderDTO~
        +confirmOrder(Long orderId) void
        +cancelOrder(Long orderId) void
    }

    class PaymentService {
        <<interface>>
        +processPayment(PaymentRequest) PaymentResult
        +handleCallback(PaymentCallback) void
        +refund(Long paymentId) RefundResult
    }

    class PurchaseOrchestrator {
        -FlashSaleService flashSaleService
        -InventoryService inventoryService
        -OrderService orderService
        -PaymentService paymentService
        +executePurchase(PurchaseRequest) PurchaseResponse
    }

    class InventoryServiceOptimistic {
        -InventoryRepository inventoryRepo
        -int maxRetries
        +reserveStock() ReservationResult
    }

    class InventoryServicePessimistic {
        -InventoryRepository inventoryRepo
        +reserveStock() ReservationResult
    }

    class InventoryServiceDistributedLock {
        -InventoryRepository inventoryRepo
        -RedisDistributedLock distributedLock
        +reserveStock() ReservationResult
    }

    InventoryService <|.. InventoryServiceOptimistic
    InventoryService <|.. InventoryServicePessimistic
    InventoryService <|.. InventoryServiceDistributedLock

    PurchaseOrchestrator --> FlashSaleService
    PurchaseOrchestrator --> InventoryService
    PurchaseOrchestrator --> OrderService
    PurchaseOrchestrator --> PaymentService
```

---

## 4. API Specifications

### 4.1 Flash Sale APIs

---

#### `POST /api/v1/flash-sales`  — Create Flash Sale (Admin)

**Headers:**
```
Authorization: Bearer <admin_jwt_token>
Content-Type: application/json
X-Request-Id: <uuid>
```

**Request Body:**
```json
{
    "productId": 1001,
    "salePrice": 99.99,
    "totalQuantity": 500,
    "maxPerUser": 1,
    "startTime": "2026-04-15T10:00:00Z",
    "endTime": "2026-04-15T10:30:00Z"
}
```

**Response — 201 Created:**
```json
{
    "id": 1,
    "productId": 1001,
    "salePrice": 99.99,
    "totalQuantity": 500,
    "maxPerUser": 1,
    "startTime": "2026-04-15T10:00:00Z",
    "endTime": "2026-04-15T10:30:00Z",
    "status": "UPCOMING",
    "createdAt": "2026-04-14T06:35:00Z"
}
```

**Error Responses:**

| Code | Body | Condition |
|------|------|-----------|
| 400 | `{"error": "INVALID_TIME_RANGE", "message": "endTime must be after startTime"}` | Invalid input |
| 401 | `{"error": "UNAUTHORIZED"}` | Missing/invalid token |
| 403 | `{"error": "FORBIDDEN"}` | Non-admin user |
| 404 | `{"error": "PRODUCT_NOT_FOUND"}` | Product doesn't exist |

---

#### `GET /api/v1/flash-sales/{id}` — Get Sale Details

**Response — 200 OK:**
```json
{
    "id": 1,
    "product": {
        "id": 1001,
        "name": "iPhone 16 Pro",
        "originalPrice": 999.99,
        "imageUrl": "https://cdn.example.com/iphone16.jpg"
    },
    "salePrice": 99.99,
    "totalQuantity": 500,
    "availableQuantity": 342,
    "maxPerUser": 1,
    "startTime": "2026-04-15T10:00:00Z",
    "endTime": "2026-04-15T10:30:00Z",
    "status": "ACTIVE",
    "discount": "90%"
}
```

---

#### `GET /api/v1/flash-sales/active` — List Active Sales

**Query Parameters:** `page=0&size=20`

**Response — 200 OK:**
```json
{
    "content": [
        { "id": 1, "product": {...}, "salePrice": 99.99, "status": "ACTIVE", ... },
        { "id": 2, "product": {...}, "salePrice": 49.99, "status": "ACTIVE", ... }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
}
```

---

### 4.2 Purchase API (Critical Path)

#### `POST /api/v1/flash-sales/{id}/purchase` — Purchase Item

**Headers:**
```
Authorization: Bearer <user_jwt_token>
Content-Type: application/json
X-Idempotency-Key: <client-generated-uuid>
X-Request-Id: <uuid>
```

**Request Body:**
```json
{
    "quantity": 1,
    "paymentMethod": "CREDIT_CARD",
    "paymentToken": "tok_visa_4242"
}
```

**Response — 200 OK:**
```json
{
    "orderId": 5001,
    "orderNumber": "FS-20260415-5001",
    "flashSaleId": 1,
    "quantity": 1,
    "unitPrice": 99.99,
    "totalAmount": 99.99,
    "status": "CONFIRMED",
    "paymentId": 3001,
    "paymentStatus": "SUCCESS",
    "createdAt": "2026-04-15T10:00:05Z"
}
```

**Error Responses:**

| Code | Body | Condition |
|------|------|-----------|
| 400 | `{"error": "INVALID_QUANTITY"}` | qty < 1 or > maxPerUser |
| 404 | `{"error": "SALE_NOT_FOUND"}` | Sale ID invalid |
| 409 | `{"error": "SOLD_OUT", "message": "This flash sale is sold out"}` | No stock remaining |
| 409 | `{"error": "ALREADY_PURCHASED", "message": "You have already purchased from this sale"}` | Duplicate purchase |
| 409 | `{"error": "SALE_NOT_ACTIVE", "message": "Sale has not started or has ended"}` | Outside sale window |
| 422 | `{"error": "PAYMENT_FAILED", "message": "Payment declined"}` | Payment rejected |
| 429 | `{"error": "RATE_LIMITED", "retryAfter": 2}` | Too many requests |

---

### 4.3 Order APIs

#### `GET /api/v1/orders/{id}` — Get Order Details

**Response — 200 OK:**
```json
{
    "id": 5001,
    "orderNumber": "FS-20260415-5001",
    "flashSaleId": 1,
    "product": {
        "name": "iPhone 16 Pro",
        "imageUrl": "https://cdn.example.com/iphone16.jpg"
    },
    "quantity": 1,
    "unitPrice": 99.99,
    "totalAmount": 99.99,
    "status": "CONFIRMED",
    "payment": {
        "transactionId": "txn_abc123",
        "status": "SUCCESS",
        "method": "CREDIT_CARD"
    },
    "createdAt": "2026-04-15T10:00:05Z"
}
```

#### `GET /api/v1/users/me/orders` — Get My Orders

**Query Parameters:** `page=0&size=20&status=CONFIRMED`

---

### 4.4 Inventory API

#### `GET /api/v1/flash-sales/{id}/inventory` — Get Real-Time Stock

**Response — 200 OK:**
```json
{
    "flashSaleId": 1,
    "totalQuantity": 500,
    "availableQuantity": 342,
    "reservedQuantity": 8,
    "soldQuantity": 150,
    "lastUpdated": "2026-04-15T10:05:32Z"
}
```

---

## 5. Sequence Diagrams

### 5.1 Purchase Flow — Optimistic Locking

```mermaid
sequenceDiagram
    actor Client
    participant Controller as PurchaseController
    participant Orchestrator as PurchaseOrchestrator
    participant SaleSvc as FlashSaleService
    participant InvSvc as InventoryService<br/>(Optimistic)
    participant DB as PostgreSQL
    participant OrderSvc as OrderService
    participant PaySvc as PaymentService
    participant Kafka

    Client->>Controller: POST /flash-sales/1/purchase<br/>Idempotency-Key: abc-123

    Controller->>Controller: Check idempotency key in Redis
    Note right of Controller: Key not found → proceed

    Controller->>Orchestrator: executePurchase(request)

    Orchestrator->>SaleSvc: validateSale(saleId=1)
    SaleSvc->>DB: SELECT * FROM flash_sales WHERE id=1
    DB-->>SaleSvc: FlashSale(status=ACTIVE)
    SaleSvc-->>Orchestrator: Valid

    loop Retry up to 3 times
        Orchestrator->>InvSvc: reserveStock(saleId=1, qty=1)
        InvSvc->>DB: SELECT * FROM inventory<br/>WHERE flash_sale_id=1
        DB-->>InvSvc: Inventory(available=342, version=150)

        InvSvc->>InvSvc: Check available >= qty

        InvSvc->>DB: UPDATE inventory<br/>SET available=341, reserved=reserved+1,<br/>version=151<br/>WHERE flash_sale_id=1 AND version=150
        alt Version matches
            DB-->>InvSvc: 1 row updated ✅
            InvSvc-->>Orchestrator: Reserved
        else Version conflict (0 rows updated)
            DB-->>InvSvc: 0 rows updated ❌
            InvSvc-->>Orchestrator: OptimisticLockException
            Note over Orchestrator: Retry with backoff
        end
    end

    Orchestrator->>OrderSvc: createOrder(...)
    OrderSvc->>DB: INSERT INTO orders(...)
    DB-->>OrderSvc: order_id=5001

    Orchestrator->>PaySvc: processPayment(orderId=5001)
    PaySvc-->>Orchestrator: PaymentResult(SUCCESS)

    Orchestrator->>OrderSvc: confirmOrder(5001)
    OrderSvc->>DB: UPDATE orders SET status='CONFIRMED'
    OrderSvc->>Kafka: Publish OrderConfirmedEvent

    Orchestrator->>InvSvc: confirmReservation(saleId=1, orderId=5001)
    InvSvc->>DB: UPDATE inventory SET reserved=reserved-1, sold=sold+1

    Orchestrator-->>Controller: PurchaseResponse
    Controller->>Controller: Store result in Redis with idempotency key
    Controller-->>Client: 200 OK {orderId: 5001}
```

### 5.2 Purchase Flow — Pessimistic Locking

```mermaid
sequenceDiagram
    actor Client
    participant Controller as PurchaseController
    participant InvSvc as InventoryService<br/>(Pessimistic)
    participant DB as PostgreSQL

    Client->>Controller: POST /flash-sales/1/purchase

    Controller->>InvSvc: reserveStock(saleId=1, qty=1)

    InvSvc->>DB: BEGIN TRANSACTION
    InvSvc->>DB: SELECT * FROM inventory<br/>WHERE flash_sale_id=1<br/>FOR UPDATE
    Note right of DB: Row is LOCKED — other<br/>transactions BLOCK here

    DB-->>InvSvc: Inventory(available=342)

    InvSvc->>InvSvc: Check available >= qty

    InvSvc->>DB: UPDATE inventory<br/>SET available=341,<br/>reserved=reserved+1
    DB-->>InvSvc: Updated

    InvSvc->>DB: INSERT INTO inventory_logs(...)
    InvSvc->>DB: COMMIT
    Note right of DB: Lock RELEASED — next<br/>transaction proceeds

    InvSvc-->>Controller: Reserved ✅
```

### 5.3 Purchase Flow — Distributed Lock (Redis Redlock)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as PurchaseController
    participant InvSvc as InventoryService<br/>(Distributed Lock)
    participant Redis as Redis Cluster
    participant DB as PostgreSQL

    Client->>Controller: POST /flash-sales/1/purchase

    Controller->>InvSvc: reserveStock(saleId=1, qty=1)

    InvSvc->>Redis: SET lock:flash_sale:1<br/>NX EX 5 value=uuid-token
    alt Lock acquired
        Redis-->>InvSvc: OK ✅

        InvSvc->>Redis: DECR flash_sale:1:stock
        Redis-->>InvSvc: 341 (remaining)

        InvSvc->>DB: UPDATE inventory SET available=341
        DB-->>InvSvc: Updated

        InvSvc->>Redis: DEL lock:flash_sale:1<br/>(only if value matches uuid-token)
        Redis-->>InvSvc: Deleted

        InvSvc-->>Controller: Reserved ✅
    else Lock not acquired
        Redis-->>InvSvc: nil ❌
        InvSvc->>InvSvc: Wait 50ms + retry (up to 3 times)
        Note over InvSvc: If all retries fail → 503 Service Busy
    end
```

---

## 6. Order State Machine

```mermaid
stateDiagram-v2
    [*] --> CREATED: User initiates purchase
    CREATED --> PAYMENT_PENDING: Inventory reserved
    PAYMENT_PENDING --> PAID: Payment successful
    PAYMENT_PENDING --> CANCELLED: Payment failed / timeout
    PAID --> CONFIRMED: Order confirmed
    CONFIRMED --> SHIPPED: Fulfillment started
    CONFIRMED --> REFUNDED: Refund requested
    CANCELLED --> [*]: Inventory released
    SHIPPED --> [*]: Delivered
    REFUNDED --> [*]: Refund processed

    note right of PAYMENT_PENDING
        Auto-cancel after 10 min
        if payment not received
    end note

    note right of CANCELLED
        Trigger inventory release
        via Kafka event
    end note
```

---

## 7. Rate Limiting Design

### Token Bucket Algorithm (Per User)

```
Configuration:
  - Bucket capacity: 5 tokens
  - Refill rate: 1 token/second
  - Applied to: POST /flash-sales/{id}/purchase

Redis Key: rate_limit:user:{userId}:purchase
Redis Value: { tokens: 5, last_refill: timestamp }
```

### Sliding Window Counter (Global)

```
Configuration:
  - Window: 1 second
  - Max requests: 10,000 per second (global)

Redis Key: rate_limit:global:purchase:{timestamp_second}
Redis Command: INCR + EXPIRE (1 second TTL)
```

---

## 8. Request Processing Pipeline

```mermaid
graph LR
    REQ["Incoming Request"] --> AUTH_FILTER["JWT Auth Filter"]
    AUTH_FILTER --> RATE_FILTER["Rate Limit Filter"]
    RATE_FILTER --> IDEMP_FILTER["Idempotency Filter"]
    IDEMP_FILTER --> VALID["Request Validation"]
    VALID --> CTRL["Controller"]
    CTRL --> SVC["Service Layer"]
    SVC --> REPO["Repository Layer"]
    REPO --> DB["Database"]

    style AUTH_FILTER fill:#e74c3c,color:#fff
    style RATE_FILTER fill:#f39c12,color:#fff
    style IDEMP_FILTER fill:#3498db,color:#fff
    style VALID fill:#2ecc71,color:#fff
```

**Filter Chain Order:**
1. **CORS Filter** — Allow cross-origin requests
2. **Request Logging Filter** — Log request ID, timestamp, path
3. **JWT Authentication Filter** — Validate token, extract user context
4. **Rate Limiting Filter** — Check token bucket, reject if exhausted
5. **Idempotency Filter** — Check if request already processed
6. **Request Validation** — Bean validation (`@Valid`)
