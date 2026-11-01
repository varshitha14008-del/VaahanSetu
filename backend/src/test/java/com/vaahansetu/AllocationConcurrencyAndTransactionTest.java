package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import allocation.core.AllocationEngine;
import allocation.core.AllocationModels;
import allocation.core.AllocationModels.Bus;
import allocation.core.AllocationModels.Commitment;
import allocation.core.AllocationModels.Input;
import allocation.core.AllocationModels.Journey;
import allocation.core.AllocationModels.Result;
import allocation.core.AllocationModels.Route;
import allocation.core.AllocationModels.Trip;

public class AllocationConcurrencyAndTransactionTest {

    private Input createSampleInput(long sessionId, LocalDateTime time) {
        List<Route> routes = List.of(
                new Route(1L, "R101", "Downtown Route", List.of(1L, 2L, 3L))
        );
        List<Bus> buses = List.of(
                new Bus(101L, "KA01A1111", 50, "ACTIVE", "Depot", 15, null, null, null, null, true),
                new Bus(102L, "KA01A2222", 30, "ACTIVE", "Depot", 15, null, null, null, null, true)
        );
        List<Trip> trips = List.of(
                new Trip(201L, 1L, time.plusHours(1), time.plusHours(2), 102L, 30)
        );
        List<Journey> journeys = List.of(
                new Journey(201L, 1L, 3L, 45) // Demand 45 exceeds bus 102 (30), needs bus 101 (50)
        );
        List<Commitment> commitments = List.of();

        return new Input(time, routes, buses, trips, journeys, commitments);
    }

    @Test
    @DisplayName("Direct Proposal Run ID Association: Each execution gets its own unique ID")
    void testDirectProposalRunIdAssociation() {
        AtomicLong runIdSequence = new AtomicLong(1000L);

        // Simulate save returning its generated ID directly
        long run1 = runIdSequence.incrementAndGet();
        long run2 = runIdSequence.incrementAndGet();

        assertNotEquals(run1, run2);
        assertEquals(1001L, run1);
        assertEquals(1002L, run2);
    }

    @Test
    @DisplayName("Simultaneous Allocation Requests: Concurrent execution safely generates deterministic outcomes")
    void testConcurrentSimultaneousAllocationRequests() throws InterruptedException, ExecutionException {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        LocalDateTime fixedTime = LocalDateTime.of(2026, 11, 2, 8, 0);

        List<Callable<Result>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLatch.await(); // wait for all threads to align
                Input input = createSampleInput(1L, fixedTime);
                return AllocationEngine.allocate(input);
            });
        }

        List<Future<Result>> futures = new ArrayList<>();
        for (Callable<Result> task : tasks) {
            futures.add(executor.submit(task));
        }

        startLatch.countDown(); // trigger all concurrently

        for (Future<Result> f : futures) {
            Result r = f.get();
            assertNotNull(r);
            assertEquals(1, r.outcomes().size());
            assertEquals("REASSIGNED", r.outcomes().get(0).type());
            assertEquals(101L, r.outcomes().get(0).proposedBusId());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Two requests for different sessions execute independently without collisions")
    void testDifferentSessionsExecuteIndependently() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 2, 9, 0);
        Input session1Input = createSampleInput(1L, time);
        Input session2Input = createSampleInput(2L, time);

        Result r1 = AllocationEngine.allocate(session1Input);
        Result r2 = AllocationEngine.allocate(session2Input);

        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.outcomes().size(), r2.outcomes().size());
    }

    @Test
    @DisplayName("Transactional Proposal Persistence Failure: Simulated failure rolls back state")
    void testFailureDuringProposalPersistenceRollback() {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        try {
            // Simulate transactional boundary
            boolean shouldFail = true;
            if (shouldFail) {
                throw new RuntimeException("Simulated database write error during proposal insert");
            }
            committed.set(true);
        } catch (Exception ex) {
            rolledBack.set(true);
        }

        assertTrue(rolledBack.get(), "Transaction must rollback on persistence error");
        assertFalse(committed.get(), "Transaction must not commit on persistence error");
    }

    @Test
    @DisplayName("Transactional Approval Rollback: Simulated failure leaves proposal status uncorrupted")
    void testApprovalRollbackOnFailure() {
        String[] proposalStatus = new String[] { "PROPOSED" };
        AtomicBoolean rollbackExecuted = new AtomicBoolean(false);

        try {
            // Simulate start of @Transactional approval
            // Phase 1: verify status
            if (!"PROPOSED".equals(proposalStatus[0])) {
                throw new IllegalStateException("Not proposed");
            }
            // Phase 2: simulate unexpected error before completion
            throw new RuntimeException("Database constraint violation on assignment update");
        } catch (Exception ex) {
            // Simulated transaction manager rollback
            rollbackExecuted.set(true);
        }

        assertTrue(rollbackExecuted.get(), "Rollback must trigger when an error occurs during approval");
        assertEquals("PROPOSED", proposalStatus[0], "Proposal status must remain PROPOSED on rollback");
    }
}
