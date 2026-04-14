# Enterprise High-Level Architecture: Component & Flow Explanation

This document explains the boxes and the data flow depicted in the `high_level_architecture.drawio` diagram.

---

## 1. Role of Each Component (The Boxes)

### Client Layer
*   **User / Browser / App**: The end-user attempting to participate in the flash sale. They generate the massive burst of concurrent HTTP requests at exactly the moment the countdown timer hits zero.

### Edge / CDN Layer (The Shield)
*   **WAF & Bot Protection**: The Web Application Firewall. Its role is to block malicious traffic, script kiddies, and crawler bots before they even reach the data center.
*   **Edge Rate Limiter**: Drops basic spam traffic. Limits users based on IP or fingerprint to a reasonable limit (e.g., 5 requests per second) to prevent simple DDOS attacks.
*   **Virtual Waiting Room (Token Issuer)**: The most critical part of handling massive scale. It knows exactly how much inventory is available. If there are 10,000 items, it issues 30,000 encrypted "Purchase Tokens". 
*   **Visual Queueing Page**: If a user does not get a token, they are routed to a static HTML page hosted on the CDN ("You are in line"). This costs the backend zero server resources.

### API Gateway Layer
*   **API Gateway**: The entry point to the actual microservices (e.g., Spring Cloud Gateway or Kong). By the time a request gets here, it is guaranteed to have a valid Purchase Token. It routes requests to the correct service and terminates SSL.

### Application Services
*   **Auth Service**: Verifies the user's identity via JWT.
*   **Flash Sale Service (L1 Caffeine Cache)**: Hosts the sale metadata (Is it live? What is the product ID?). It stores this data in application RAM (L1 Cache) for 1-5 seconds so it doesn't have to query the database.
*   **Inventory Engine**: The core processing unit. It does not talk to a SQL database. It only talks to Redis.
*   **Order Service**: Creates the formal record of the order. Runs asynchronously to avoid blocking the user.
*   **Payment Service**: Integrates with Stripe/Razorpay to finalize the financial transaction.

### Data & Event Layer
*   **Redis Cluster (Sharded Inventory)**: Holds the actual stock count in memory. Because a single Redis key can bottleneck CPU, stock is "sharded" (split) across multiple nodes.
*   **Kafka Event Bus**: The nervous system of the architecture. Instead of services waiting on each other, they drop "Events" into Kafka.
*   **DynamoDB / Cassandra (High-Write Order Intake)**: A NoSQL database designed to handle thousands of writes per second without requiring table locks. Used for the initial surge of incoming orders.
*   **PostgreSQL (Financial Truth)**: The highly structured relational database. Used to store the final, paid order and generate accounting reports.

---

## 2. Step-by-Step Request Flow

Here is exactly what happens when 1 Million users click "Buy" at the exact same second:

### Phase 1: The Edge Filter
1. **1 Million requests** hit the Edge/CDN Layer.
2. The **WAF** immediately drops 100k bot requests.
3. The **Virtual Waiting Room** sees that the sale only has 10,000 items. To allow for payment failures, it issues exactly 30,000 cryptographic **Purchase Tokens**.
4. The remaining **870,000 users** are instantly redirected to the **Visual Queueing Page** without ever touching the application servers.

### Phase 2: The Gateway & Validation
5. The **30,000 fortunate users** are routed to the **API Gateway**.
6. The Gateway validates their token and forwards the request to the **Inventory Engine**.
7. The **Flash Sale Service** is pinged, which instantly returns "Sale is Active" directly from its in-memory **L1 Caffeine Cache**.

### Phase 3: The Async Inventory Reservation (The Bottleneck Solution)
8. The **Inventory Engine** runs a Lua script on the **Redis Cluster**.
9. Instead of trying to update a slow SQL database, the Lua script atomically decrements the stock in RAM.
10. **If stock > 0**: Redis confirms the reservation.
11. The Inventory Engine immediately publishes an **`InventoryReserved` Event** to **Kafka**.
12. The HTTP request ends here! The user gets a response: *`HTTP 202 Accepted: Order is processing.`* (Total time: ~50ms).

### Phase 4: Order Creation & Payment
13. Meanwhile, securely in the background, the **Order Service** consumes the event from **Kafka**.
14. It batch-inserts the initial order data into **DynamoDB/Cassandra** to handle the write-heavy load without deadlocking.
15. A new **`OrderCreated` Event** is sent back to Kafka.
16. The user's app polls the server: "Is my order ready?". The server replies: "Yes, here is your Order ID. Proceed to payment."
17. The user pays via the **Payment Service**.
18. Upon successful payment, the final, permanent record is safely stored in **PostgreSQL**.
