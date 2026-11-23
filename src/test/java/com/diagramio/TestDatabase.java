package com.diagramio;

/**
 * Image PostgreSQL cho mọi test dùng Testcontainers. Cùng phiên bản chính với máy đo hiệu năng
 * (Kaggle, bench/kaggle/setup.sh) và docker-compose.yml, để kế hoạch truy vấn và hành vi SQL khớp nhau.
 */
public final class TestDatabase {

    public static final String POSTGRES_IMAGE = "postgres:16-alpine";

    private TestDatabase() {
    }
}
