package com.cognizant.emk.multiagent.domain.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level marker for a Spring bean that should appear as one entry in the tool
 * catalog (design §13, REQ-TOOL-001 / -003).
 *
 * <p>Decoupled from Spring AI's {@code @Tool} method annotation by design: a bean
 * declares its catalog identity here, and exposes per-method {@code @Tool} methods
 * separately for runtime execution (EPIC-11). The infrastructure
 * {@code ToolCatalogAdapter} (US-07-002) reads this annotation reflectively at
 * startup and builds one {@link ToolDescriptor} per annotated bean.
 *
 * <p>Pure-Java metadata — no Spring imports — so the annotation lives in the domain
 * layer alongside the {@link ToolDescriptor} it produces.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolGroup {

    /** Catalog entry name. Must be unique across all beans and ≤ 64 chars. */
    String name();

    /** Human-readable description surfaced through {@code GET /tools}. */
    String description();
}
