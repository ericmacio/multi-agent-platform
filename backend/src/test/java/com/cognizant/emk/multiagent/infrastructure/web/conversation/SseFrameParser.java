package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-side helper that parses a raw SSE response body into an ordered
 * list of typed frames. Each frame is `event: NAME\ndata: JSON\n\n`;
 * the parser splits on the blank-line frame separator and extracts the
 * {@code event} name and the {@code data} JSON for each.
 *
 * <p>Used by US-11-007's full integration test to assert the documented
 * frame sequence without depending on a third-party SSE client.
 */
final class SseFrameParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseFrameParser() {}

    static List<Frame> parse(String body) throws IOException {
        List<Frame> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        // Frames are separated by a blank line. Use `\n\n` rather than the
        // CR-LF spec form because Spring's SseEmitter emits LF-only.
        String[] blocks = body.split("\n\n");
        for (String block : blocks) {
            if (block.isBlank()) continue;
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("event:")) {
                    name = trimmed.substring("event:".length()).trim();
                } else if (trimmed.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(trimmed.substring("data:".length()).trim());
                }
            }
            if (name != null) {
                JsonNode dataJson = data.length() == 0
                        ? MAPPER.nullNode()
                        : MAPPER.readTree(data.toString());
                out.add(new Frame(name, dataJson));
            }
        }
        return out;
    }

    record Frame(String name, JsonNode data) {}
}
