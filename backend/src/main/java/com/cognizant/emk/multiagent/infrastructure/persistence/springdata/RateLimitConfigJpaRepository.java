package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.RateLimitConfigJpa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigJpaRepository extends JpaRepository<RateLimitConfigJpa, Short> {
}
