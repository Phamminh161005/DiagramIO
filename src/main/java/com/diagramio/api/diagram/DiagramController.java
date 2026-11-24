package com.diagramio.api.diagram;

import com.diagramio.api.ConditionalRequests;
import com.diagramio.api.CurrentUser;
import com.diagramio.api.diagram.dto.CreateDiagramRequest;
import com.diagramio.api.diagram.dto.DiagramResponse;
import com.diagramio.api.diagram.dto.DiagramSummaryResponse;
import com.diagramio.api.diagram.dto.UpdateDiagramRequest;
import com.diagramio.api.dto.ErrorResponse;
import com.diagramio.api.dto.PageResponse;
import com.diagramio.config.OpenApiConfig;
import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramAccessInfo;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramService;
import com.diagramio.domain.diagram.DiagramType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/diagrams")
@Tag(name = "Diagrams", description = "Quản lý sơ đồ UML/ERD: tạo, xem, sửa (optimistic locking), xóa")
@ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "Chưa đăng nhập",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class DiagramController {

    private final DiagramService diagramService;

    public DiagramController(DiagramService diagramService) {
        this.diagramService = diagramService;
    }

    @GetMapping
    @Operation(summary = "Danh sách sơ đồ của tôi",
            description = "Lọc theo loại (UML/ERD), tìm theo tên, phân trang (size tối đa 100). "
                    + "Sắp xếp: mới sửa trước (updatedAt giảm dần), cùng thời điểm thì theo id, nên thứ tự giữa "
                    + "các trang ổn định.")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @ApiResponse(responseCode = "200", description = "Một trang danh sách sơ đồ (không kèm data)")
    public PageResponse<DiagramSummaryResponse> getMyDiagrams(
            @Parameter(description = "Tìm theo tên (không phân biệt hoa thường)") @RequestParam(required = false) String title,
            @Parameter(description = "UML hoặc ERD") @RequestParam(required = false) String diagramType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UUID ownerId) {
        DiagramType typeFilter = diagramType == null || diagramType.isBlank() ? null : DiagramType.from(diagramType);
        return pageResponse(diagramService.listMine(ownerId, typeFilter, title, page, size), page, size);
    }

    @PostMapping
    @Operation(summary = "Tạo sơ đồ mới",
            description = "Sơ đồ mới luôn riêng tư, data mặc định {\"nodes\": [], \"edges\": []}. Người tạo là owner.")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @ApiResponse(responseCode = "201", description = "Đã tạo sơ đồ")
    public ResponseEntity<DiagramResponse> create(
            @Valid @RequestBody CreateDiagramRequest request,
            @CurrentUser UUID ownerId) {
        Diagram diagram = new Diagram();
        diagram.setTitle(request.title());
        diagram.setDiagramType(DiagramType.from(request.diagramType()));
        diagram.setOwnerId(ownerId);
        Diagram created = diagramService.createDiagram(diagram);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(DiagramResponse.from(created));
    }

    @GetMapping("/public")
    @Operation(summary = "Danh sách sơ đồ công khai",
            description = "Không cần đăng nhập. Sắp xếp: mới sửa trước (updatedAt giảm dần), rồi theo id.")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Một trang danh sách sơ đồ công khai (không kèm data)")
    public PageResponse<DiagramSummaryResponse> getPublicDiagrams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pageResponse(diagramService.listPublic(page, size), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết sơ đồ kèm data",
            description = "Sơ đồ công khai: ai cũng xem được. Riêng tư: owner hoặc người có link chia sẻ (view/edit). "
                    + "Response có ETag W/\"<version>\"; gửi lại ETag đó trong If-None-Match thì nhận 304 "
                    + "không kèm nội dung nếu sơ đồ chưa đổi. Quyền xem luôn được kiểm tra trước, nên người không "
                    + "có quyền vẫn nhận 401/403/404 chứ không bao giờ nhận 304.")
    @ApiResponse(responseCode = "200", description = "Chi tiết sơ đồ", headers = {
            @Header(name = HttpHeaders.ETAG, description = "W/\"<version>\"", schema = @Schema(types = "string")),
            @Header(name = HttpHeaders.CACHE_CONTROL, description = "no-cache, private",
                    schema = @Schema(types = "string"))})
    @ApiResponse(responseCode = "304", description = "Sơ đồ chưa đổi so với ETag trong If-None-Match (không có nội dung)",
            content = @Content)
    @ApiResponse(responseCode = "403", description = "Không có quyền xem",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Không tìm thấy sơ đồ",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<DiagramResponse> getById(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_NONE_MATCH,
                    description = "ETag của bản client đang có, ví dụ W/\"3\"")
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Caller caller) {
        if (ifNoneMatch != null) {
            // Kiểm tra quyền và version mà không đọc data; chưa đổi thì không gửi lại nội dung
            DiagramAccessInfo info = diagramService.getAccessInfo(id, caller);
            String etag = ConditionalRequests.diagramEtag(info.version());
            if (ConditionalRequests.matches(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
                        .cacheControl(ConditionalRequests.REVALIDATE).build();
            }
        }
        return withEtag(diagramService.getDiagramById(id, caller));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật sơ đồ",
            description = "Owner hoặc người có link edit. Bắt buộc gửi version đang thấy; lệch thì 409. "
                    + "data phải là object có mảng nodes và edges, tối đa 2 MB. Chỉ owner đổi được isPublic.")
    @ApiResponse(responseCode = "200", description = "Đã cập nhật, version tăng 1; ETag là của version mới",
            headers = @Header(name = HttpHeaders.ETAG, description = "W/\"<version>\"",
                    schema = @Schema(types = "string")))
    @ApiResponse(responseCode = "403", description = "Không có quyền sửa",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Không tìm thấy sơ đồ",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Sơ đồ đã bị người khác sửa (version lệch)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<DiagramResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDiagramRequest request,
            Caller caller) {
        return withEtag(diagramService.updateDiagram(
                id, request.title(), DiagramType.from(request.diagramType()),
                DiagramData.of(request.data()),
                request.isPublic(),
                caller, request.version()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa sơ đồ", description = "Chỉ owner.")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @ApiResponse(responseCode = "204", description = "Đã xóa")
    @ApiResponse(responseCode = "403", description = "Không phải owner",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Không tìm thấy sơ đồ",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> delete(@PathVariable UUID id, Caller caller) {
        diagramService.deleteDiagram(id, caller);
        return ResponseEntity.noContent().build();
    }

    /** ETag theo version mới nhất và Cache-Control: no-cache, để client hỏi lại bằng If-None-Match. */
    private static ResponseEntity<DiagramResponse> withEtag(Diagram diagram) {
        return ResponseEntity.ok()
                .eTag(ConditionalRequests.diagramEtag(diagram.getVersion()))
                .cacheControl(ConditionalRequests.REVALIDATE)
                .body(DiagramResponse.from(diagram));
    }

    private PageResponse<DiagramSummaryResponse> pageResponse(DiagramPage result, int page, int size) {
        List<DiagramSummaryResponse> content = result.items().stream()
                .map(DiagramSummaryResponse::from)
                .toList();
        long total = result.total();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
