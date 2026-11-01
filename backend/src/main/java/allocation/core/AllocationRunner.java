
package allocation.core;

import java.time.LocalDateTime;
import java.util.OptionalLong;

public class AllocationRunner {

    private static final long POLL_MILLIS = 5000;

    public static void main(String[] args) throws Exception {

        AllocationService service = new AllocationService();
        AllocationRepository repo = service.repository();

        String lastSeen = null;
        long lastSession = -1;

        while (true) {

            try {

                OptionalLong session;

                try (var c = repo.open()) {
                    session = repo.activeSessionId(c);
                }

                if (session.isPresent()) {

                    long sessionId = session.getAsLong();

                    LocalDateTime allocationTime = args.length > 0
                            ? LocalDateTime.parse(args[0])
                            : LocalDateTime.now();

                    String previousFingerprint = sessionId == lastSession ? lastSeen : null;

                    AllocationService.ReallocationCheck check = service.reallocateIfChanged(
                            sessionId,
                            allocationTime,
                            previousFingerprint);

                    if (check.reallocated()) {

                        AllocationModels.Result result = check.result();

                        System.out.printf(
                                "[%s] session %d: demand %d, unmet %d -> %d%n",
                                LocalDateTime.now(),
                                sessionId,
                                result.beforeTotal().demand(),
                                result.beforeTotal().unmet(),
                                result.afterTotal().unmet());
                    }

                    lastSession = sessionId;
                    lastSeen = check.fingerprint();
                }

            } catch (Exception e) {

                System.err.println(
                        "Allocation failed; will retry: " + e.getMessage());
            }

            Thread.sleep(POLL_MILLIS);
        }
    }
}
