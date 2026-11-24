package com.diagramio.domain.diagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.domain.exception.InvalidInputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DiagramDataTest {

    @Test
    void ofAcceptsObjectWithNodesAndEdgesArrays() {
        String json = "{\"nodes\": [{\"id\": \"n1\"}], \"edges\": [], \"meta\": {\"zoom\": 1}}";

        assertThat(DiagramData.of(json).json()).isEqualTo(json);
    }

    @Test
    void ofRejectsWrongStructure() {
        for (String json : new String[] {"{\"foo\": 1}", "{\"nodes\": \"abc\", \"edges\": []}",
                "{\"nodes\": []}", "\"{\\\"nodes\\\": []}\"", "[]", "not json", "", "   ", null}) {
            assertThatThrownBy(() -> DiagramData.of(json))
                    .as(String.valueOf(json))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageStartingWith("data must be");
        }
    }

    @Test
    void ofRejectsMoreThanTwoMegabytes() {
        String padding = "x".repeat(DiagramData.MAX_BYTES);
        String json = "{\"nodes\": [], \"edges\": [], \"pad\": \"" + padding + "\"}";

        assertThatThrownBy(() -> DiagramData.of(json))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("data must not exceed 2 MB");
    }

    @Test
    void emptyIsValidAndMatchesSpecDefault() {
        assertThat(DiagramData.of(DiagramData.EMPTY.json())).isEqualTo(DiagramData.EMPTY);
        assertThat(DiagramData.EMPTY.json()).isEqualTo("{\"nodes\": [], \"edges\": []}");
    }

    @Test
    void fromStorageSkipsValidationButRejectsNull() {
        assertThat(DiagramData.fromStorage("{}").json()).isEqualTo("{}");
        assertThatThrownBy(() -> DiagramData.fromStorage(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityFollowsJsonText() {
        assertThat(DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}")).isEqualTo(DiagramData.EMPTY);
        assertThat(DiagramData.fromStorage("{\"nodes\":[],\"edges\":[]}")).isNotEqualTo(DiagramData.EMPTY);
    }

    // ---- of(JsonNode): body đã được Jackson parse, không parse lại ----

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ofTreeAcceptsTheSameShapeAndSerializesItOnce() throws Exception {
        JsonNode tree = MAPPER.readTree("{\"nodes\": [{\"id\": \"n1\"}], \"edges\": [], \"meta\": {\"zoom\": 1}}");

        assertThat(DiagramData.of(tree).json())
                .isEqualTo("{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[],\"meta\":{\"zoom\":1}}");
    }

    @Test
    void ofTreeRejectsWrongStructure() throws Exception {
        for (String json : new String[] {"{\"foo\": 1}", "{\"nodes\": \"abc\", \"edges\": []}",
                "{\"nodes\": []}", "\"text\"", "[]", "null", "1"}) {
            JsonNode tree = MAPPER.readTree(json);
            assertThatThrownBy(() -> DiagramData.of(tree))
                    .as(json)
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageStartingWith("data must be");
        }
        assertThatThrownBy(() -> DiagramData.of((JsonNode) null)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void ofTreeCountsBytesNotCharacters() throws Exception {
        // 1,1 triệu ký tự 2 byte: dưới 2 triệu ký tự nhưng trên 2 MB khi mã hóa UTF-8
        String padding = "ư".repeat(1_100_000);
        JsonNode tree = MAPPER.readTree("{\"nodes\": [], \"edges\": [], \"pad\": \"" + padding + "\"}");

        assertThatThrownBy(() -> DiagramData.of(tree))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("data must not exceed 2 MB");
    }

    @Test
    void ofTreeAcceptsExactlyTheLimit() throws Exception {
        String prefix = "{\"nodes\":[],\"edges\":[],\"pad\":\"";
        String padding = "x".repeat(DiagramData.MAX_BYTES - prefix.length() - 2);
        JsonNode tree = MAPPER.readTree(prefix + padding + "\"}");

        assertThat(DiagramData.of(tree).json()).hasSize(DiagramData.MAX_BYTES);
    }
}
