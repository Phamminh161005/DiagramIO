package com.diagramio.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RawJsonSerializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Holder(String name, @JsonSerialize(using = RawJsonSerializer.class) String data, int version) {
    }

    /** Ghi ra luồng byte: UTF8JsonGenerator, như khi Spring MVC ghi response HTTP. */
    private static String bytesOutput(Holder holder) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MAPPER.writeValue(out, holder);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Ghi ra Writer: WriterBasedJsonGenerator. */
    private static String writerOutput(Holder holder) throws Exception {
        StringWriter out = new StringWriter();
        MAPPER.writeValue(out, holder);
        return out.toString();
    }

    @Test
    void smallValueIsWrittenRawInsideTheObject() throws Exception {
        Holder holder = new Holder("a", "{\"nodes\": [], \"edges\": []}", 2);
        String expected = "{\"name\":\"a\",\"data\":{\"nodes\": [], \"edges\": []},\"version\":2}";

        assertThat(bytesOutput(holder)).isEqualTo(expected);
        assertThat(writerOutput(holder)).isEqualTo(expected);
    }

    @Test
    void valueLargerThanTheOutputBufferWithMultibyteCharactersIsExact() throws Exception {
        String json = "{\"nodes\": [" + "{\"label\": \"Sơ đồ – 😀 \\\"x\\\"\"},".repeat(5000)
                + "{}], \"edges\": []}";
        Holder holder = new Holder("ư", json, 7);
        String expected = "{\"name\":\"ư\",\"data\":" + json + ",\"version\":7}";

        assertThat(bytesOutput(holder)).isEqualTo(expected);
        assertThat(writerOutput(holder)).isEqualTo(expected);
        assertThat(MAPPER.readTree(bytesOutput(holder)).get("data").get("nodes")).hasSize(5001);
    }

    @Test
    void valuesAroundTheBufferSizeTakeBothPathsCorrectly() throws Exception {
        // quanh 8 KB: có lần chép được vào bộ đệm, có lần phải ghi thẳng mảng byte ra output
        for (int chars : new int[] {10, 3_990, 4_000, 4_050, 7_950, 8_000, 8_100, 16_000}) {
            String json = "{\"nodes\": [], \"edges\": [], \"pad\": \"" + "é".repeat(chars) + "\"}";
            Holder holder = new Holder("x".repeat(100), json, 1);
            assertThat(bytesOutput(holder)).as("chars " + chars)
                    .isEqualTo("{\"name\":\"" + "x".repeat(100) + "\",\"data\":" + json + ",\"version\":1}");
        }
    }
}
