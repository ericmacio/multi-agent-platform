package com.cognizant.emk.multiagent.infrastructure.persistence.springdata;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.JwtDenylistJpa;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JwtDenylistJpaRepository extends JpaRepository<JwtDenylistJpa, UUID> {
}
