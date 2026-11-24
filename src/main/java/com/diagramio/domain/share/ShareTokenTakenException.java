package com.diagramio.domain.share;

import com.diagramio.domain.exception.ConflictException;

/**
 * Token chia sẻ vừa sinh trùng một token đã có (vi phạm UNIQUE). Với 24 byte ngẫu nhiên gần như không xảy ra;
 * {@link ShareService} sinh token khác và thử lại.
 */
public class ShareTokenTakenException extends ConflictException {

    public ShareTokenTakenException() {
        super("Share token already exists");
    }
}
