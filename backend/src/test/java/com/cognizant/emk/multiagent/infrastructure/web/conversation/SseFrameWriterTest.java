package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.application.chat.TurnEvent;
import com.cognizant.emk.multiagent.infrastructure.web.error.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class SseFrameWriterTest {

    private SseFrameWriter writer;
    private CapturingEmitter emitter;

    @BeforeEach
    void setUp() {
        writer = new SseFrameWriter(new ObjectMapper());
        emitter = new CapturingEmitter();
    }

    @Test
    void started_event_writes_a_started_frame_with_documented_payload() throws Exception {
        UUID uid = UUID.fromString("11111111-2222-4333-8444-555555555555");
        UUID cid = UUID.fromString("66666666-7777-4888-9999-aaaaaaaaaaaa");

        writer.write(emitter, new TurnEvent.Started(uid, cid));

        assertThat(emitter.frames).hasSize(1);
        CapturedFrame f = emitter.frames.get(0);
        assertThat(f.name).isEqualTo("started");
        assertThat(f.data).contains("\"userMessageId\":\"" + uid + "\"");
        assertThat(f.data).contains("\"conversationId\":\"" + cid + "\"");
    }

    @Test
    void delta_event_with_non_empty_text_writes_a_delta_frame() throws Exception {
        writer.write(emitter, new TurnEvent.Delta("hello"));

        assertThat(emitter.frames).hasSize(1);
        assertThat(emitter.frames.get(0).name).isEqualTo("delta");
        assertThat(emitter.frames.get(0).data).isEqualTo("{\"text\":\"hello\"}");
    }

    @Test
    void delta_event_with_empty_text_is_elided() throws Exception {
        writer.write(emitter, new TurnEvent.Delta(""));

        assertThat(emitter.frames).as("empty deltas are elided per §7.1").isEmpty();
    }

    @Test
    void completed_event_writes_a_completed_frame_with_non_null_title() throws Exception {
        UUID aid = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        writer.write(emitter, new TurnEvent.Completed(aid, "first-turn", 2));

        assertThat(emitter.frames).hasSize(1);
        assertThat(emitter.frames.get(0).name).isEqualTo("completed");
        assertThat(emitter.frames.get(0).data).contains("\"title\":\"first-turn\"");
        assertThat(emitter.frames.get(0).data).contains("\"messageCount\":2");
    }

    @Test
    void completed_event_with_null_title_serializes_as_json_null() throws Exception {
        writer.write(emitter, new TurnEvent.Completed(UUID.randomUUID(), null, 4));

        // Default Jackson serializes null record fields as `null`.
        assertThat(emitter.frames.get(0).data).contains("\"title\":null");
    }

    @Test
    void write_error_writes_an_error_frame_with_problem_details_body() throws Exception {
        ProblemDetails body = ProblemDetails.of(
                "LLM_UNAVAILABLE",
                "LLM unavailable",
                502,
                "The language-model provider is currently unavailable.",
                "/api/v1/conversations/abc/messages");

        writer.writeError(emitter, body);

        assertThat(emitter.frames).hasSize(1);
        assertThat(emitter.frames.get(0).name).isEqualTo("error");
        assertThat(emitter.frames.get(0).data).contains("\"code\":\"LLM_UNAVAILABLE\"");
        assertThat(emitter.frames.get(0).data).contains("\"status\":502");
    }

    // ----- helpers -----

    private record CapturedFrame(String name, String data) {}

    /** Minimal SseEmitter override that captures (event-name, data-string) pairs. */
    private static final class CapturingEmitter extends SseEmitter {
        final List<CapturedFrame> frames = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder.build() returns the SSE record split into many small
            // DataWithMediaType chunks (event: prefix, name, \n, data: prefix,
            // payload, \n, terminator \n). Concatenate everything and parse the
            // resulting `event: NAME\ndata: JSON\n` block.
            Set<org.springframework.web.servlet.mvc.method.annotation
                    .ResponseBodyEmitter.DataWithMediaType> data = builder.build();
            StringBuilder all = new StringBuilder();
            for (org.springframework.web.servlet.mvc.method.annotation
                    .ResponseBodyEmitter.DataWithMediaType d : data) {
                if (d.getData() != null) {
                    all.append(d.getData().toString());
                }
            }
            String raw = all.toString();
            String name = null;
            String body = null;
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("event:")) {
                    name = trimmed.substring("event:".length()).trim();
                } else if (trimmed.startsWith("data:")) {
                    body = trimmed.substring("data:".length()).trim();
                }
            }
            frames.add(new CapturedFrame(name, body == null ? "" : body));
        }

        @Override
        public void send(Object object, MediaType mediaType) {
            // Not used by the writer
        }
    }
}
