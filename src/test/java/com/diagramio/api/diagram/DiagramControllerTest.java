package com.diagramio.api.diagram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramio.api.GlobalExceptionHandler;
import com.diagramio.config.SecurityConfig;
import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramService;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.DiagramNotFoundException;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import com.diagramio.domain.share.ShareService;
import com.diagramio.infrastructure.security.JwtTokenProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DiagramController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class DiagramControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DIAGRAM_ID = UUID.randomUUID();
    // data gửi lên là object JSON; controller chuyển thành chuỗi gọn trước khi xuống domain
    private static final String SENT_DATA = "{\"nodes\":[],\"edges\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiagramService diagramService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider; // SecurityConfig cần; mặc định coi mọi token là không hợp lệ

    @MockitoBean
    private ShareService shareService; // filter X-Share-Token cần; mặc định coi mọi token là không hợp lệ

    @Test
    void createReturns201ForValidRequest() throws Exception {
        when(diagramService.createDiagram(any(Diagram.class))).thenReturn(diagram());

        mockMvc.perform(post("/api/v1/diagrams")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"ERD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/diagrams/" + DIAGRAM_ID));
    }

    @Test
    void createReturns400ForInvalidDiagramType() throws Exception {
        mockMvc.perform(post("/api/v1/diagrams")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"BPMN"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns200ForValidRequest() throws Exception {
        when(diagramService.updateDiagram(eq(DIAGRAM_ID), eq("Orders"), eq(DiagramType.ERD),
                eq(DiagramData.fromStorage(SENT_DATA)), eq(false), eq(Caller.user(USER_ID)), eq(1)))
                .thenReturn(diagram());

        mockMvc.perform(put("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"ERD","version":1,
                                 "data":{"nodes": [], "edges": []},"isPublic":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DIAGRAM_ID.toString()));
    }

    @Test
    void updateReturns409ForVersionConflict() throws Exception {
        when(diagramService.updateDiagram(eq(DIAGRAM_ID), eq("Orders"), eq(DiagramType.ERD),
                eq(DiagramData.fromStorage(SENT_DATA)), eq(false), eq(Caller.user(USER_ID)), eq(1)))
                .thenThrow(new OptimisticLockConflictException(DIAGRAM_ID, 1, 2));

        mockMvc.perform(put("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"ERD","version":1,
                                 "data":{"nodes": [], "edges": []},"isPublic":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Diagram version conflict: requested 1, current 2"));
    }

    @Test
    void deleteReturns403ForNonOwner() throws Exception {
        doThrow(new ForbiddenException("Only the diagram owner can delete it"))
                .when(diagramService).deleteDiagram(DIAGRAM_ID, Caller.user(USER_ID));

        mockMvc.perform(delete("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReturns404WhenDiagramDoesNotExist() throws Exception {
        when(diagramService.getDiagramById(DIAGRAM_ID, Caller.anonymous()))
                .thenThrow(new DiagramNotFoundException("Diagram not found: " + DIAGRAM_ID));

        mockMvc.perform(get("/api/v1/diagrams/{id}", DIAGRAM_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateWithEditShareTokenPassesShareCallerWithoutLogin() throws Exception {
        Caller editor = Caller.shareToken(DIAGRAM_ID, ShareRole.EDIT);
        when(shareService.resolveShareToken("edit-token")).thenReturn(Optional.of(editor));
        when(diagramService.updateDiagram(eq(DIAGRAM_ID), eq("Orders"), eq(DiagramType.ERD),
                eq(DiagramData.fromStorage(SENT_DATA)), eq(false), eq(editor), eq(1)))
                .thenReturn(diagram());

        mockMvc.perform(put("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .header("X-Share-Token", "edit-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"ERD","version":1,
                                 "data":{"nodes": [], "edges": []},"isPublic":false}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateWithUnknownShareTokenReturns401() throws Exception {
        mockMvc.perform(put("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .header("X-Share-Token", "revoked-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Orders","diagramType":"ERD","version":1,
                                 "data":{"nodes": [], "edges": []},"isPublic":false}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteWithEditShareTokenIsForbidden() throws Exception {
        Caller editor = Caller.shareToken(DIAGRAM_ID, ShareRole.EDIT);
        when(shareService.resolveShareToken("edit-token")).thenReturn(Optional.of(editor));
        doThrow(new ForbiddenException("Only the diagram owner can delete it"))
                .when(diagramService).deleteDiagram(DIAGRAM_ID, editor);

        mockMvc.perform(delete("/api/v1/diagrams/{id}", DIAGRAM_ID)
                        .header("X-Share-Token", "edit-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void responseDataIsJsonObject() throws Exception {
        when(diagramService.getDiagramById(DIAGRAM_ID, Caller.anonymous())).thenReturn(diagram());

        mockMvc.perform(get("/api/v1/diagrams/{id}", DIAGRAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andExpect(jsonPath("$.data.edges").isArray());
    }

    @Test
    void myDiagramsCallsTheServiceOnceAndMapsThePage() throws Exception {
        Instant at = Instant.parse("2026-11-24T10:00:00Z");
        DiagramSummary summary = new DiagramSummary(DIAGRAM_ID, "Orders", DiagramType.ERD, 3, false, USER_ID,
                USER_ID, at, at);
        when(diagramService.listMine(USER_ID, DiagramType.ERD, "ord", 1, 2))
                .thenReturn(new DiagramPage(List.of(summary), 5));

        mockMvc.perform(get("/api/v1/diagrams").param("diagramType", "ERD").param("title", "ord")
                        .param("page", "1").param("size", "2")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(DIAGRAM_ID.toString()))
                .andExpect(jsonPath("$.content[0].version").value(3))
                .andExpect(jsonPath("$.content[0].data").doesNotExist())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        // một lần gọi service = một câu SQL (trang + tổng), không gọi riêng để đếm
        verify(diagramService).listMine(USER_ID, DiagramType.ERD, "ord", 1, 2);
        verifyNoMoreInteractions(diagramService);
    }

    @Test
    void myDiagramsNeedsLogin() throws Exception {
        mockMvc.perform(get("/api/v1/diagrams"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicDiagramsNeedNoLoginAndReportZeroPagesWhenEmpty() throws Exception {
        when(diagramService.listPublic(0, 20)).thenReturn(new DiagramPage(List.of(), 0));

        mockMvc.perform(get("/api/v1/diagrams/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void largeUnicodeDataIsWrittenVerbatim() throws Exception {
        // lớn hơn bộ đệm 8 KB của Jackson và có ký tự nhiều byte: phải ra đúng từng byte UTF-8
        String json = "{\"nodes\": [" + "{\"label\": \"Đơn hàng – khách 😀\"},".repeat(2000)
                + "{\"label\": \"end\"}], \"edges\": []}";
        Diagram diagram = diagram();
        diagram.setData(DiagramData.fromStorage(json));
        when(diagramService.getDiagramById(DIAGRAM_ID, Caller.anonymous())).thenReturn(diagram);

        String body = mockMvc.perform(get("/api/v1/diagrams/{id}", DIAGRAM_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body).contains("\"data\":" + json + ",\"version\":1");
    }

    private Diagram diagram() {
        return new Diagram(DIAGRAM_ID, "Orders", DiagramType.ERD, DiagramData.EMPTY, 1,
                false, USER_ID, USER_ID, null, null);
    }
}
