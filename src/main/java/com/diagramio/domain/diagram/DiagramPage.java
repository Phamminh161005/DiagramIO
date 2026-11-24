package com.diagramio.domain.diagram;

import java.util.List;

/** Một trang danh sách sơ đồ (mới sửa trước) và tổng số sơ đồ khớp điều kiện. */
public record DiagramPage(List<DiagramSummary> items, long total) {

    public DiagramPage {
        items = List.copyOf(items);
    }
}
