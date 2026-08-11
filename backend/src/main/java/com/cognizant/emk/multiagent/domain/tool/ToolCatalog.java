package com.cognizant.emk.multiagent.domain.tool;

import java.util.List;
import java.util.Optional;

/**
 * Read-only port over the static tool catalog (design §13, REQ-TOOL-001 / -003).
 *
 * <p>The catalog is populated once at application startup by
 * {@code ToolCatalogAdapter} (US-07-002) and cached for the lifetime of the JVM.
 * Implementations MUST be thread-safe — {@link #all()} returns an unmodifiable
 * snapshot.
 */
public interface ToolCatalog {

    /** Returns every catalog entry, sorted by {@code name} for deterministic output. */
    List<ToolDescriptor> all();

    /**
     * Returns {@code true} when {@code name} matches a catalog entry. Case-sensitive,
     * matching the {@code agent_tools.tool_name} column collation. Backs the agent
     * write-time reference validator (US-07-005, REQ-TOOL-004).
     */
    boolean contains(String name);

    /**
     * Resolve a tool name to its underlying {@code @ToolGroup}-annotated bean
     * instance. The bean carries the {@code @Tool}-annotated methods Spring AI
     * introspects at chat time to build function definitions.
     *
     * <p>Returns {@link Optional#empty()} when {@code name} is unknown. Callers
     * that have already validated against {@link #contains(String)} can safely
     * unwrap; everything else should treat an empty optional as an unknown tool.
     *
     * <p>The return type is {@link Object} on purpose — tools are plain POJOs
     * and the domain port stays free of Spring AI types.
     */
    Optional<Object> resolveBean(String name);
}
