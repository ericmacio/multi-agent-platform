package com.cognizant.emk.multiagent.infrastructure.web.pagination;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageDtoTest {

    private CursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CursorCodec();
    }

    @Test
    void of_maps_each_item_through_the_supplied_mapper() {
        Page<String> page = new Page<>(List.of("alpha", "beta"), null, 20);

        PageDto<Integer> dto = PageDto.of(page, codec, String::length);

        assertThat(dto.items()).containsExactly(5, 4);
        assertThat(dto.pageSize()).isEqualTo(20);
        assertThat(dto.nextCursor()).isNull();
    }

    @Test
    void of_encodes_the_next_cursor_via_the_codec_when_one_is_present() {
        Cursor cursor = new Cursor(OffsetDateTime.of(2026, 5, 12, 8, 0, 0, 0, ZoneOffset.UTC), "abc");
        Page<String> page = new Page<>(List.of("alpha"), cursor, 1);

        PageDto<String> dto = PageDto.of(page, codec, s -> s);

        assertThat(dto.nextCursor()).isEqualTo(codec.encode(cursor));
        // The encoded cursor must decode back to the original.
        assertThat(codec.decode(dto.nextCursor())).isEqualTo(cursor);
    }

    @Test
    void of_emits_null_next_cursor_when_the_page_is_the_last() {
        Page<String> page = new Page<>(List.of("only"), null, 10);
        PageDto<String> dto = PageDto.of(page, codec, s -> s);
        assertThat(dto.nextCursor()).isNull();
    }

    @Test
    void of_preserves_an_empty_items_list_intact() {
        Page<String> empty = new Page<>(List.of(), null, 25);
        PageDto<String> dto = PageDto.of(empty, codec, s -> s);
        assertThat(dto.items()).isEmpty();
        assertThat(dto.pageSize()).isEqualTo(25);
        assertThat(dto.nextCursor()).isNull();
    }
}
