package com.vaahansetu.repository;

import javax.sql.DataSource;

import org.springframework.stereotype.Repository;

/**
 * Spring-managed repository wrapper delegating to allocation.core.AllocationRepository.
 */
@Repository
public class AllocationRepository extends allocation.core.AllocationRepository {

    public AllocationRepository(DataSource dataSource) {
        super(dataSource);
    }
}
