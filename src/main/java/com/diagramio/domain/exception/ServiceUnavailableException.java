package com.diagramio.domain.exception;

/**
 * Tạm thời không thực hiện được thao tác một cách an toàn, client nên thử lại (HTTP 503). Ví dụ: không ghi được dấu
 * "link đang đổi" lên cache dùng chung, nên nếu vẫn thu hồi link thì instance khác có thể còn dùng quyền cũ.
 */
public class ServiceUnavailableException extends DomainException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
