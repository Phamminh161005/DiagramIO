package com.diagramio.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Ghi một chuỗi JSON đã hợp lệ (data của sơ đồ, tới 2 MB) vào response nguyên văn, như {@code @JsonRawValue}
 * nhưng nhanh hơn nhiều với chuỗi lớn.
 *
 * <p>{@code @JsonRawValue} gọi {@code writeRawValue(String)}: generator UTF-8 chép chuỗi ra mảng char rồi mã hóa
 * từng ký tự sang UTF-8 ({@code _writeRawSegment}); flame graph M0 cho thấy việc này chiếm 83% CPU của app ở
 * kịch bản sơ đồ lớn (W3). Ở đây chuỗi được mã hóa một lần bằng {@link String#getBytes} (có intrinsic của JVM,
 * với chuỗi ASCII gần như chỉ là chép mảng) và generator ghi thẳng mảng byte đó ra output.
 */
public class RawJsonSerializer extends StdSerializer<String> {

    public RawJsonSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String json, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeRawValue(new Utf8Raw(json));
    }

    /**
     * Chuỗi raw (không thêm ngoặc kép, không thoát ký tự). Generator UTF-8 chỉ gọi {@link #appendUnquotedUTF8}
     * rồi {@link #asUnquotedUTF8}; generator ghi ra Writer gọi {@link #appendUnquoted} rồi {@link #getValue}.
     * Các phương thức dạng "quoted" không dùng cho giá trị raw, chuyển cho {@link SerializedString}.
     */
    static final class Utf8Raw implements SerializableString {

        private final String json;

        Utf8Raw(String json) {
            this.json = json;
        }

        @Override
        public String getValue() {
            return json;
        }

        @Override
        public int charLength() {
            return json.length();
        }

        @Override
        public byte[] asUnquotedUTF8() {
            return json.getBytes(StandardCharsets.UTF_8);
        }

        /** Trả -1 khi không vừa bộ đệm (8 KB) để generator ghi thẳng mảng byte ra output. */
        @Override
        public int appendUnquotedUTF8(byte[] buffer, int offset) {
            if (json.length() > buffer.length - offset) {
                return -1;
            }
            byte[] bytes = asUnquotedUTF8();
            if (bytes.length > buffer.length - offset) {
                return -1;
            }
            System.arraycopy(bytes, 0, buffer, offset, bytes.length);
            return bytes.length;
        }

        @Override
        public int appendUnquoted(char[] buffer, int offset) {
            if (json.length() > buffer.length - offset) {
                return -1;
            }
            json.getChars(0, json.length(), buffer, offset);
            return json.length();
        }

        @Override
        public int writeUnquotedUTF8(OutputStream out) throws IOException {
            byte[] bytes = asUnquotedUTF8();
            out.write(bytes);
            return bytes.length;
        }

        @Override
        public int putUnquotedUTF8(ByteBuffer buffer) {
            byte[] bytes = asUnquotedUTF8();
            if (bytes.length > buffer.remaining()) {
                return -1;
            }
            buffer.put(bytes);
            return bytes.length;
        }

        @Override
        public char[] asQuotedChars() {
            return quoted().asQuotedChars();
        }

        @Override
        public byte[] asQuotedUTF8() {
            return quoted().asQuotedUTF8();
        }

        @Override
        public int appendQuotedUTF8(byte[] buffer, int offset) {
            return quoted().appendQuotedUTF8(buffer, offset);
        }

        @Override
        public int appendQuoted(char[] buffer, int offset) {
            return quoted().appendQuoted(buffer, offset);
        }

        @Override
        public int writeQuotedUTF8(OutputStream out) throws IOException {
            return quoted().writeQuotedUTF8(out);
        }

        @Override
        public int putQuotedUTF8(ByteBuffer buffer) throws IOException {
            return quoted().putQuotedUTF8(buffer);
        }

        private SerializedString quoted() {
            return new SerializedString(json);
        }

        @Override
        public String toString() {
            return json;
        }
    }
}
