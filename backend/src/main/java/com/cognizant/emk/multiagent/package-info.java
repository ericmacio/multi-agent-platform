/**
 * Multi-Agent Platform backend root package.
 *
 * <p>Layering rule (enforced by ArchUnit, see {@code arch.LayeringArchTest}):
 * <pre>
 *     infrastructure  -&gt;  application  -&gt;  domain
 * </pre>
 *
 * <ul>
 *   <li>{@code domain} — pure Java; no Spring, JPA, Spring AI, Jackson, or Lombok.</li>
 *   <li>{@code application} — use cases and technical ports; may use Spring stereotypes
 *       ({@code @Service}, {@code @Transactional}) but not Spring MVC or JPA.</li>
 *   <li>{@code infrastructure} — adapters that implement domain or application ports
 *       (REST, persistence, LLM, MCP, security, configuration).</li>
 * </ul>
 *
 * <p>Inside each layer, packages are organized by bounded context (user, agent, conversation,
 * tool, mcp, ratelimit, auth) rather than by technical kind.
 */
package com.cognizant.emk.multiagent;
