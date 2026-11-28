package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CachedDiagramTest {

    @Test
    void latinOneTextTakesOneBytePerCharacter() {
        assertThat(CachedDiagram.heapBytes("{\"label\":\"Order\"}")).isEqualTo(17);
        assertThat(CachedDiagram.heapBytes("café")).isEqualTo(4);   // é là Latin-1
        assertThat(CachedDiagram.heapBytes("")).isZero();
        assertThat(CachedDiagram.heapBytes(null)).isZero();
    }

    @Test
    void oneVietnameseCharacterMakesTheWholeStringTwoBytesPerCharacter() {
        String json = "{\"nodes\":[{\"label\":\"" + "x".repeat(1000) + " Đơn hàng\"}],\"edges\":[]}";

        assertThat(CachedDiagram.heapBytes(json)).isEqualTo(2L * json.length());
    }

    @Test
    void weightCountsDataAndTitleAsTheyAreStoredInTheHeap() {
        String data = "{\"nodes\":[{\"label\":\"Sơ đồ\"}],\"edges\":[]}";
        CachedDiagram entry = CachedDiagram.of(new Diagram(UUID.randomUUID(), "Orders", DiagramType.ERD,
                DiagramData.fromStorage(data), 1, false, UUID.randomUUID(), null, null, null));

        assertThat(entry.weight()).isEqualTo(256 + 2 * data.length() + "Orders".length());
        assertThat(CachedDiagram.tombstone(UUID.randomUUID()).weight()).isEqualTo(256);
    }
}
