package com.diagramio.infrastructure.cache;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.share.DiagramShare;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Nơi lưu cache tra cứu token chia sẻ (B3, ADR-0004). Khóa là SHA-256 của token, không bao giờ là token gốc; giá trị
 * là link ({@link Entry}) hoặc "không có" (cache âm).
 *
 * <p>Thu hồi và đổi quyền phải có hiệu lực ngay, kể cả khi có một lần tra cứu đọc CSDL trước lúc thay đổi nhưng ghi
 * vào cache sau đó. Vì vậy trước khi đổi CSDL, {@link #markChanging} đặt một dấu theo {@code shareId}, sống ít nhất
 * bằng thời gian sống của mục dương. Trong lúc có dấu: tra cứu ra {@link Kind#BYPASS} (đi CSDL), và lần nạp của link
 * đó bị từ chối. Mục dương nào có trước lúc đặt dấu đều hết hạn trước khi dấu hết hạn, nên sau khi dấu hết hạn không
 * còn bản cũ nào, dù việc xóa khóa sau commit có thành công hay không.
 */
public interface ShareTokenStore {

    enum Kind {
        /** Có link trong cache. */
        HIT,
        /** Cache âm: token không tồn tại (hoặc vừa bị thu hồi). */
        ABSENT,
        /** Không có trong cache. */
        MISS,
        /** Link đang được đổi quyền hoặc thu hồi: phải hỏi CSDL, không được nạp vào cache. */
        BYPASS
    }

    /** Link trong cache; không chứa token gốc. */
    record Entry(UUID shareId, UUID diagramId, ShareRole role, Instant createdAt) {

        public static Entry of(DiagramShare share) {
            return new Entry(share.getId(), share.getDiagramId(), share.getRole(), share.getCreatedAt());
        }

        public DiagramShare toShare(String token) {
            return new DiagramShare(shareId, diagramId, token, role, createdAt);
        }

        /** Dạng chuỗi cho Redis: {@code shareId|diagramId|role|createdAt}. */
        String encode() {
            return shareId + "|" + diagramId + "|" + role.value() + "|" + createdAt;
        }

        static Entry decode(String value) {
            String[] f = value.split("\\|", 4);
            return new Entry(UUID.fromString(f[0]), UUID.fromString(f[1]), ShareRole.from(f[2]), Instant.parse(f[3]));
        }
    }

    record Lookup(Kind kind, Entry entry) {
        static final Lookup ABSENT = new Lookup(Kind.ABSENT, null);
        static final Lookup MISS = new Lookup(Kind.MISS, null);
        static final Lookup BYPASS = new Lookup(Kind.BYPASS, null);

        static Lookup hit(Entry entry) {
            return new Lookup(Kind.HIT, entry);
        }
    }

    Lookup lookup(String tokenHash);

    /**
     * Ghi kết quả vừa đọc từ CSDL ({@code entry} null = không có), chỉ khi khóa chưa có gì và link không mang dấu
     * đang đổi. Không bao giờ đè lên giá trị đang có.
     */
    void fill(String tokenHash, Entry entry);

    /** Link vừa được tạo: ghi đè (xóa cả cache âm nếu từng có người thử token này). */
    void put(String tokenHash, Entry entry);

    /**
     * Đặt dấu "đang đổi" cho link, gọi TRƯỚC khi đổi CSDL. Ném lỗi nếu không đặt được: khi đó không được đổi CSDL,
     * vì instance khác có thể còn dùng quyền cũ trong cache.
     */
    void markChanging(UUID shareId);

    /** Bỏ khóa sau khi đổi quyền, thu hồi hoặc xóa sơ đồ. */
    void drop(String tokenHash);

    /** SHA-256 (hex) của token: khóa của cache. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
