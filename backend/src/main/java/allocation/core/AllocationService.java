
package allocation.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

public class AllocationService {

    private final AllocationRepository repo;

    public AllocationService() {
        this(new AllocationRepository());
    }

    public AllocationService(AllocationRepository repo) {
        this.repo = repo;
    }

    public AllocationRepository repository() {
        return repo;
    }

    public record ReallocationCheck(
            String fingerprint,
            AllocationModels.Result result,
            boolean reallocated) {
    }

    public ReallocationCheck reallocateIfChanged(
            long sessionId,
            LocalDateTime allocationTime,
            String previousFingerprint) throws SQLException {

        try (Connection c = repo.open()) {

            c.setAutoCommit(false);

            try {

                AllocationModels.Input input = repo.load(c, sessionId, allocationTime);

                String fingerprint = repo.inputFingerprint(input);

                if (fingerprint.equals(previousFingerprint)) {
                    c.commit();

                    return new ReallocationCheck(
                            fingerprint, null, false);
                }

                AllocationModels.Result result = AllocationEngine.allocate(input);

                repo.save(c, sessionId, result);

                c.commit();

                return new ReallocationCheck(
                        fingerprint, result, true);

            } catch (SQLException | RuntimeException e) {

                c.rollback();
                throw e;
            }
        }
    }

    public AllocationModels.Result reallocate(
            long sessionId,
            LocalDateTime allocationTime) throws SQLException {

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
