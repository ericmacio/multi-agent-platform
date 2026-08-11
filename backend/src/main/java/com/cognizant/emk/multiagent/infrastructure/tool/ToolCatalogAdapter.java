package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.tool.ToolGroup;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * Spring-backed implementation of {@link ToolCatalog} (design §13, REQ-TOOL-001 / -003).
 *
 * <p>At startup ({@link PostConstruct}) the adapter walks the
 * {@link ApplicationContext} and finds every bean annotated with {@link ToolGroup}.
 * For each one, it builds a {@link ToolDescriptor} from the annotation values (the
 * descriptor's canonical constructor enforces length / blank rules), checks for
 * name collisions, and caches the result. The catalog is immutable for the rest of
 * the JVM lifetime.
 *
 * <p>Duplicate names fail the application at startup with a clear error naming both
 * offending bean classes — no silent override, no chat-time surprise.
 */
@Component
public class ToolCatalogAdapter implements ToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogAdapter.class);

    private final ApplicationContext applicationContext;

    private List<ToolDescriptor> snapshot = List.of();
    private Map<String, ToolDescriptor> byName = Map.of();
    private Map<String, Object> beanByName = Map.of();

    public ToolCatalogAdapter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void scan() {
        Map<String, Object> annotated = applicationContext.getBeansWithAnnotation(ToolGroup.class);
        Map<String, ToolDescriptor> collected = new LinkedHashMap<>();
        Map<String, Object> collectedBeans = new LinkedHashMap<>();
        // Track which bean class first declared each name so duplicate diagnostics
        // surface both class names.
        Map<String, Class<?>> firstDeclarer = new HashMap<>();

        for (Map.Entry<String, Object> entry : annotated.entrySet()) {
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();
            ToolGroup group = AnnotationUtils.findAnnotation(beanClass, ToolGroup.class);
            if (group == null) {
                // Defensive — getBeansWithAnnotation looks at proxy classes too; the
                // direct lookup above handles CGLIB proxies that don't carry the
                // annotation on the proxy class itself.
                continue;
            }

            // Constructing the descriptor enforces the structural rules (≤ 64 chars
            // name, non-blank fields) via ValidationException at startup.
            ToolDescriptor descriptor = new ToolDescriptor(group.name(), group.description());

            Class<?> existing = firstDeclarer.putIfAbsent(descriptor.name(), beanClass);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate tool catalog entry '" + descriptor.name()
                                + "' declared by both " + existing.getName()
                                + " and " + beanClass.getName());
            }
            collected.put(descriptor.name(), descriptor);
            collectedBeans.put(descriptor.name(), bean);
        }

        List<ToolDescriptor> sorted = collected.values().stream()
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();

        this.snapshot = Collections.unmodifiableList(sorted);
        this.byName = Collections.unmodifiableMap(new HashMap<>(collected));
        this.beanByName = Collections.unmodifiableMap(new HashMap<>(collectedBeans));
        log.info("Tool catalog populated with {} entries: {}",
                snapshot.size(), snapshot.stream().map(ToolDescriptor::name).toList());
    }

    @Override
    public List<ToolDescriptor> all() {
        return snapshot;
    }

    @Override
    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    @Override
    public Optional<Object> resolveBean(String name) {
        return Optional.ofNullable(beanByName.get(name));
    }
}
