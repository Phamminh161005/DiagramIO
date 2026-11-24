package com.diagramio.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chạy cập nhật cache sau khi transaction hiện tại commit (rollback thì bỏ), hoặc chạy ngay nếu không có
 * transaction (câu lệnh đã autocommit). Nhờ vậy cache không bao giờ chứa trạng thái chưa commit. Lỗi của việc cập
 * nhật cache chỉ được ghi log: dữ liệu đã nằm an toàn trong CSDL.
 */
final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(action);
                }
            });
        } else {
            safely(action);
        }
    }

    /** Có transaction thật đang chạy: đọc phải thấy cả những gì chính transaction này vừa ghi, nên không đọc cache. */
    static boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Không cập nhật được cache sau khi ghi", e);
        }
    }
}
