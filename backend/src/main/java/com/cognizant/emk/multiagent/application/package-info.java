/**
 * Application layer — use cases and technical (non-repository) ports.
 *
 * <p>May depend on Spring stereotypes ({@code @Service}, {@code @Transactional}) but NOT on
 * Spring MVC ({@code org.springframework.web..}) or JPA ({@code jakarta.persistence..}).
 * Use-case interfaces sit beside their {@code @Service} implementations in the same
 * bounded-context package; no separate {@code port/in} folder.
 */
package com.cognizant.emk.multiagent.application;
