## 1. Optimistic Locking
**The Concept**: You assume conflicts are rare. Instead of locking the data when you read it, you let the transaction proceed and only check for interference at the very end (during the "commit" phase).

**Note**: Here First we need to read data (version) then write.

**Mechanism**: Usually involves a version number or timestamp.

**Best for**: Read-heavy workloads with low contention (low probability of two users hitting the same row at the exact same time).

**Pros**: Highly scalable; no database locks are held during processing.

**Cons**: If a conflict occurs, the transaction fails and must be retried by the application.


```java
@Entity
public class Inventory {
    @Id
    private Long id;
    
    private int stockCount;

    @Version
    private Long version; // Managed automatically by Hibernate

    public void updateStock(int amount) {
        this.stockCount -= amount;
    }
}
```

## 2. Pessimistic Locking
**The Concept**: You assume the worst. You lock the record the moment you read it, preventing anyone else from even touching it until you are finished.

**Mechanism**: Uses database-level locks (e.g., SELECT ... FOR UPDATE).

**Best for**: Write-heavy workloads with high contention where the cost of retrying (optimistic) is higher than the cost of waiting.

**Pros**: Guaranteed data integrity; no need for retry logic.

**Cons**: Can lead to deadlocks and performance bottlenecks because other threads are blocked.

```java
public void updateStockPessimistic(Long productId, int amount) {
    // This issues a "SELECT ... FOR UPDATE"
    Inventory inventory = entityManager.find(
        Inventory.class, 
        productId, 
        LockModeType.PESSIMISTIC_WRITE
    );

    inventory.setStockCount(inventory.getStockCount() - amount);
    // Lock is released when the transaction commits
}
```