package com.diagramio.api.share;

import com.diagramio.api.ConditionalRequests;
import com.diagramio.api.dto.ErrorResponse;
import com.diagramio.api.share.dto.SharedDiagramResponse;
import com.diagramio.domain.share.ShareService;
import com.diagramio.domain.share.SharedDiagramView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shares")
@SecurityRequirements
@Tag(name = "Public Sharing", description = "Truy xuất sơ đồ công khai thông qua mã chia sẻ (Không yêu cầu đăng nhập)")
public class PublicShareController {

    private final ShareService shareService;

    public PublicShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @GetMapping("/{token}")
    @ApiResponse(responseCode = "200", description = "Sơ đồ và quyền của token (view/edit)", headers =
            @Header(name = HttpHeaders.ETAG, description = "W/\"<version>-<role>\"",
                    schema = @Schema(types = "string")))
    @ApiResponse(responseCode = "304", description = "Sơ đồ và quyền của link chưa đổi so với If-None-Match",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "Token không tồn tại hoặc đã bị thu hồi",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @Operation(summary = "Truy xuất thông tin và cấu trúc sơ đồ qua token chia sẻ", description = "Ai có token "
            + "hợp lệ đều có thể xem cấu trúc sơ đồ và quyền tương ứng (view/edit). ETag W/\"<version>-<role>\" "
            + "đổi khi sơ đồ đổi hoặc quyền của link đổi; link đã thu hồi luôn trả 404, kể cả khi gửi If-None-Match.")
    public ResponseEntity<SharedDiagramResponse> getSharedDiagram(
            @PathVariable("token") String token,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_NONE_MATCH,
                    description = "ETag của bản client đang có, ví dụ W/\"3-view\"")
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        SharedDiagramView diagramView = shareService.getDiagramByShareToken(token);
        String etag = ConditionalRequests.sharedDiagramEtag(diagramView.getVersion(), diagramView.getRole().value());
        if (ConditionalRequests.matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
                    .cacheControl(ConditionalRequests.REVALIDATE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(ConditionalRequests.REVALIDATE)
                .body(SharedDiagramResponse.fromDomain(diagramView));
    }
}
