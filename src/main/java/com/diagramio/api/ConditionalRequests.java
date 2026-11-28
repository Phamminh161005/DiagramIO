package com.diagramio.api;

import java.time.Duration;
import org.springframework.http.CacheControl;

/**
 * HTTP conditional request (B2): ETag theo version của sơ đồ, {@code If-None-Match} trả 304.
 *
 * <p>ETag là loại yếu ({@code W/"<version>"}): mỗi lần sửa hay khôi phục, version tăng nên ETag đổi, nhưng cùng một
 * version có thể được gửi ở hai dạng byte khác nhau mà nội dung như nhau (JSON gọn như client gửi lên ngay sau PUT,
 * hoặc dạng text đã chuẩn hóa của {@code jsonb} khi đọc từ CSDL). ETag mạnh hứa giống từng byte nên không đúng ở đây;
 * {@code If-None-Match} vẫn so được ETag yếu.
 */
public final class ConditionalRequests {

    /** Client phải hỏi lại server mỗi lần dùng (có thể nhận 304); proxy dùng chung không được lưu. */
    public static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();
    /** Phiên bản đã lưu là snapshot bất biến: client giữ được mãi, không cần hỏi lại. */
    public static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private ConditionalRequests() {
    }

    public static String diagramEtag(int version) {
        return "W/\"" + version + "\"";
    }

    /** Qua link chia sẻ, response còn chứa quyền của link: đổi quyền cũng phải đổi ETag. */
    public static String sharedDiagramEtag(int version, String role) {
        return "W/\"" + version + "-" + role + "\"";
    }

    /**
     * {@code If-None-Match} có khớp ETag không, theo phép so yếu của RFC 9110 mục 13.1.2: bỏ tiền tố {@code W/} rồi
     * so phần trong ngoặc kép; {@code *} khớp mọi ETag. Header không có hoặc sai cú pháp thì không khớp.
     */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String expected = opaque(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*") || (expected != null && expected.equals(opaque(tag)))) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String tag) {
        String value = tag.startsWith("W/") ? tag.substring(2) : tag;
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return null;
        }
        return value.substring(1, value.length() - 1);
    }
}
