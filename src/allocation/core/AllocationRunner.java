package allocation.core;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.OptionalLong;

/** Optional polling runner. It writes to the database; do not run until a test DB is configured. */
public class AllocationRunner {
    private static final long POLL_MILLIS = 5000;
    public static void main(String[] args) throws Exception {
        AllocationService service = new AllocationService();
        AllocationRepository repo = service.repository();
        String lastSeen = null;
        long lastSession = -1;
        while (true) {
            try (Connection c = repo.open()) {
                OptionalLong session = repo.activeSessionId(c);
                if (session.isPresent()) {
                    String fingerprint = repo.demandFingerprint(c, session.getAsLong());
                    if (session.getAsLong() != lastSession || !fingerprint.equals(lastSeen)) {
                        LocalDateTime allocationTime = args.length > 0
                                ? LocalDateTime.parse(args[0]) : LocalDateTime.now();
                        AllocationModels.Result result = service.reallocate(session.getAsLong(), allocationTime);
                        lastSession = session.getAsLong();
                        lastSeen = fingerprint;
                        System.out.printf("[%s] session %d: demand %d, unmet %d -> %d%n",
                                LocalDateTime.now(), lastSession, result.beforeTotal().demand(),
                                result.beforeTotal().unmet(), result.afterTotal().unmet());
                    }
                }
            } catch (Exception e) {
                System.err.println("Allocation failed; will retry: " + e.getMessage());
            }
            Thread.sleep(POLL_MILLIS);
        }
    }
}
