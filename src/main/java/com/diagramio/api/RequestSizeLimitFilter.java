package com.diagramio.api;

import com.diagramio.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chặn body request quá lớn TRƯỚC khi đọc và parse (HTTP 413).
 *
 * <p>Giới hạn 2 MB của {@code data} chỉ được kiểm tra sau khi Jackson đã parse cả body thành cây JSON. Không có lớp
 * chặn này, một body 66 MB (vài trăm MB trong heap khi thành cây) được đọc hết rồi mới bị từ chối; vài request như vậy
 * cùng lúc làm app hết bộ nhớ (đã thử: 6 request, heap 512 MB, OutOfMemoryError). Body có {@code Content-Length} vượt
 * giới hạn bị từ chối ngay mà không đọc; body gửi kiểu chunked (không có độ dài) được đếm khi đọc và dừng ở byte đầu
 * tiên vượt giới hạn.
 *
 * <p>Giới hạn này là của tầng vận chuyển, rộng hơn quy tắc 2 MB của {@code data} (chừa chỗ cho các trường khác và
 * khoảng trắng); quy tắc 2 MB vẫn do {@code DiagramData} kiểm tra và trả 400.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(long maxBytes, ObjectMapper objectMapper) {
        this.maxBytes = maxBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(request, response);
            return;
        }
        chain.doFilter(declared < 0 ? new LimitedRequest(request, maxBytes) : request, response);
    }

    /** Thông báo lỗi cho client, giống nhau dù phát hiện qua Content-Length hay lúc đang đọc body chunked. */
    static String message(long maxBytes) {
        return "Request body must not exceed " + (maxBytes >> 20) + " MB";
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Phần body chưa đọc không cần nhận tiếp: đóng kết nối sau response này
        response.setHeader(HttpHeaders.CONNECTION, "close");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message(maxBytes), request.getRequestURI()));
    }

    /** Lỗi khi body chunked vượt giới hạn; GlobalExceptionHandler đổi thành 413 với chính thông báo này. */
    public static class RequestTooLargeException extends IOException {
        public RequestTooLargeException(long maxBytes) {
            super(message(maxBytes));
        }
    }

    /** Request mà luồng body đếm số byte đã đọc và ném lỗi ở byte đầu tiên vượt giới hạn. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return limited;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws RequestTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new RequestTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
