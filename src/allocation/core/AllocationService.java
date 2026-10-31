package allocation.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Loads inputs, runs the pure allocator, and saves both baseline and proposed results atomically. */
public class AllocationService {
    private final AllocationRepository repo;
    public AllocationService() { this(new AllocationRepository()); }
    public AllocationService(AllocationRepository repo) { this.repo = repo; }
    public AllocationRepository repository() { return repo; }

    public AllocationModels.Result reallocate(long sessionId, LocalDateTime allocationTime) throws SQLException {
        try (Connection c = repo.open()) {
            c.setAutoCommit(false);
            try {
                AllocationModels.Input input = repo.load(c, sessionId, allocationTime);
                AllocationModels.Result result = AllocationEngine.allocate(input);
                repo.save(c, sessionId, result);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
