# ADR-0002: Tối ưu truy cập dữ liệu (B1)

- Trạng thái: Đã triển khai và đo (phiên `b12`, [kết quả](../pha-2/ket-qua.md) mục 3)
- Mốc: B1 trong [kế hoạch pha 2](../ke-hoach-pha-2.md)
- Liên quan: [ADR-0001](0001-phuong-phap-do.md) (phương pháp đo), [danh-gia-hien-trang.md](../pha-2/danh-gia-hien-trang.md) (số đo C0)

## Bối cảnh

Đánh giá hiện trạng (M0, phiên Kaggle `20261124-061459-m0`) cho thấy ở kịch bản chính W2 **PostgreSQL là tài nguyên bão hòa trước**: ở 200 req/s PostgreSQL đã dùng 0,74 nhân so với 0,40 của app; khi bão hòa (khoảng 400 req/s) 200/200 luồng Tomcat bận và tới 191 request xếp hàng chờ 10 kết nối của pool. Thời gian của CSDL dồn vào vài câu lệnh, và phần lớn là việc không cần làm:

| Câu lệnh (C0, W2 500 req/s) | % thời gian CSDL | Vì sao thừa |
|---|---|---|
| Đếm sơ đồ công khai | 46% (64 ms mỗi lần) | Quét tuần tự 50.000 dòng; chạy 2 lần mỗi request vì controller gọi service hai lần |
| Trang "sơ đồ của tôi" | 16% | Kéo cả cột `data` (tới 2 MB mỗi dòng) dù response không trả |
| Tìm theo tên | 15% | `upper(title) LIKE upper(?)` không dùng được index nào |
| Trang công khai có `offset` | 9% | Không có `ORDER BY`, không có index |
| Đọc sơ đồ theo id | 7% | Kiểm tra quyền đọc cả sơ đồ, kể cả khi thao tác không cần nội dung |

Bảng số câu SQL mỗi request (`SqlPerRequestE2eTest`) xác nhận các giả thuyết ở mục 2.3 của kế hoạch: sửa sơ đồ 3 câu và chở `data` 3 lần, lưu phiên bản 4 câu, khôi phục 6 câu, danh sách 4 câu và chở `data` của cả trang, lịch sử chở `data` của mọi phiên bản. W3 (sơ đồ lớn) còn cho thấy một điểm nghẽn ở app: 83% CPU nằm trong `UTF8JsonGenerator._writeRawSegment`, tức là `@JsonRawValue` mã hóa lại từng ký tự của chuỗi `data`.

## Quyết định

Chiến thuật: **giảm tài nguyên mỗi request**, không thêm thành phần mới. Mọi thay đổi nằm trong `infrastructure` và `api`; domain chỉ đổi hợp đồng của port (thêm phương thức), vẫn là Java thuần (`ArchitectureTest` xanh).

1. **Kiểm tra quyền không đọc `data`.** Record domain `DiagramAccessInfo(id, ownerId, isPublic, version)`; `Caller`, `Permission` làm việc trên nó. `DiagramAccess` có ba cách đọc: `require` (4 cột), `requireLocked` (4 cột, `SELECT ... FOR UPDATE`, dùng trong transaction lưu và khôi phục phiên bản) và `requireDiagram` (cả sơ đồ, chỉ khi response trả nội dung).
2. **PUT là một câu ghi có điều kiện.** `UPDATE diagrams SET ..., version = version + 1 WHERE id = :id AND version = :expected RETURNING version, created_at, updated_at`. Response dùng chính `data` vừa gửi lên nên `data` không quay lên app. Không dòng nào khớp thì đọc lại `version` hiện tại để trả 409 (hoặc 404 nếu sơ đồ vừa bị xóa). Không cần transaction: người ghi chen vào thì `version` đã đổi và câu UPDATE không sửa gì; `DiagramVersionConcurrencyTest` kiểm tra 20 luồng cùng sửa từ một version thì đúng một người thắng.
3. **Snapshot và khôi phục chép `data` ngay trong CSDL.** `INSERT INTO diagram_versions ... SELECT ... FROM diagrams` (số thứ tự `max + 1` tính trong cùng câu), `UPDATE diagrams d SET data = v.data ... FROM diagram_versions v`. Vẫn khóa dòng sơ đồ trước (`requireLocked`) nên các yêu cầu đồng thời xếp hàng như pha 1; `UNIQUE (diagram_id, version_number)` vẫn là lưới an toàn. Bản sao lưu trước khi khôi phục chỉ trả về tóm tắt (`DiagramVersionSummary`), đúng với hợp đồng API.
4. **Danh sách là projection, một câu lệnh.** `DiagramSummary`, `DiagramPage(items, total)`; controller gọi service một lần. Câu SQL: `SELECT t.total, p.* FROM (SELECT count(*) ...) t LEFT JOIN LATERAL (SELECT <cột tóm tắt> ... ORDER BY updated_at DESC, id LIMIT :n OFFSET :o) p ON true`: trang và tổng cùng một snapshot, một lần mượn kết nối, trang rỗng vẫn có tổng. Luôn có `ORDER BY updated_at DESC, id` nên thứ tự giữa các trang ổn định (sửa lỗi đúng đắn của pha 1: phân trang không có thứ tự). Lịch sử phiên bản cũng là projection không có `data`.
5. **Index** (`V4__phase2_indexes.sql`): `(owner_id, updated_at DESC, id)` thay cho `idx_diagrams_owner`; `(updated_at DESC, id) WHERE is_public` (index một phần, ~10% số dòng); GIN trigram trên `title` (extension `pg_trgm`, loại "trusted" nên chủ CSDL tự tạo được); `diagram_shares(diagram_id)`. Tìm tên viết lại thành `title ILIKE :q ESCAPE '\'`, ký tự `%`, `_`, `\` của người dùng được thoát nên vẫn là tìm chuỗi con như trước.
6. **Thêm mới không qua `merge`.** `users`, `refresh_tokens` cài `Persistable<UUID>` với cờ `isNew` tạm thời, nên Spring Data gọi `persist` (một INSERT) thay vì `merge` (SELECT rồi INSERT). `diagram_shares` thêm bằng một câu INSERT; bỏ câu kiểm tra token trùng, dựa vào ràng buộc UNIQUE và sinh token khác khi trùng (tối đa 3 lần). Thu hồi refresh token là `UPDATE ... WHERE id = :id AND revoked = false`: hai request dùng cùng một token thì chỉ một request được cấp token mới. Đổi quyền và thu hồi link là một câu có điều kiện thuộc sơ đồ.
7. **Chỉ parse JSON một lần.** `DiagramData.of(JsonNode)` kiểm tra cấu trúc trên cây Jackson đã có rồi serialize một lần (trước đây: `toString()` rồi parse lại). Giới hạn 2 MB tính theo byte UTF-8.
8. **Ghi `data` ra response bằng mảng byte.** `RawJsonSerializer` thay `@JsonRawValue`: chuỗi được mã hóa một lần bằng `String.getBytes(UTF_8)` (intrinsic của JVM) và generator ghi thẳng mảng byte. Đầu ra giống hệt từng byte (có test với chuỗi lớn hơn bộ đệm 8 KB và ký tự nhiều byte).
9. **Tắt observation không dùng.** `management.observations.enable.spring.security=false` và `long-task-timer.enabled=false` (flame graph W2: 7,9% CPU app). `http.server.requests` vẫn đo như cũ. Bộ đo đã bật hai cờ này cho mọi cấu hình từ M0 nên phép so sánh C0 và C1 không bị lệch.

Bảng `diagrams` và `diagram_versions` chỉ dùng SQL viết tay (`NamedParameterJdbcTemplate`), bỏ entity và repository JPA của hai bảng này. Lý do: test tích hợp phát hiện khi JPA và JDBC chạy chung một transaction, entity JPA đã nạp không thấy các câu UPDATE/DELETE của JDBC, nên đọc lại sau khi ghi trả bản cũ. Ở pha 1 điều đó chưa lộ vì mỗi request một session, nhưng B2 (cache write-through) sẽ đọc lại ngay sau khi ghi. Quy tắc này được ghi vào `AGENTS.md`.

## Các phương án đã cân nhắc

| Vấn đề | Phương án | Chọn và lý do |
|---|---|---|
| Đếm tổng cho danh sách | Hai câu (trang, COUNT); `count(*) OVER ()` trong câu lấy trang; COUNT riêng nối bằng `LATERAL` | **LATERAL.** `count(*) OVER ()` buộc PostgreSQL đọc mọi dòng khớp rồi mới cắt trang, không dùng được quét index top-N theo `ORDER BY ... LIMIT`; hai câu thì hai lần mượn kết nối và có thể lệch snapshot |
| Phân trang trang sâu | `OFFSET`; con trỏ (keyset) | **Giữ `OFFSET`** để không đổi hợp đồng API (`page`, `size`). Với index đúng thứ tự, `OFFSET` chỉ đi trên index và không chạm `data`; W2 đọc trang công khai theo Zipf trên 10 trang đầu. Keyset là hướng tiếp theo nếu cần trang rất sâu |
| PUT | Giữ JPA `@Version` (đọc, merge, ghi); native query trong `JpaRepository`; `NamedParameterJdbcTemplate` | **JdbcTemplate**: `RETURNING` và chép trong CSDL dễ viết, kiểm soát được từng cột; vẫn gọn trong một adapter |
| Kiểu cột `data` | `jsonb` (hiện tại); `json` | **Giữ `jsonb`** ở B1. `json` rẻ hơn lúc ghi và đọc, nhưng mất dạng chuẩn hóa mà B7 cần để băm nội dung; chỉ đổi nếu số đo W3 cho thấy lợi rõ |
| Serialize `data` | `@JsonRawValue`; parse thành `JsonNode` rồi ghi cây; serializer ghi mảng byte | **Mảng byte**: không parse, không mã hóa từng ký tự |

## Hệ quả

Số câu lệnh mỗi request, đo bằng `SqlPerRequestE2eTest` (sơ đồ 50 KB, không phụ thuộc phần cứng; "Lần chở data" là số lần cột `data` đi giữa app và CSDL):

| Endpoint | C0: SQL / lần chở data | C1: SQL / lần chở data |
|---|---|---|
| `PUT /diagrams/{id}` | 3 / 3 | 2 / 1 |
| `PUT /diagrams/{id}` version cũ (409) | 1 / 1 | 1 / 0 |
| `POST /diagrams/{id}/versions` | 4 / 2 | 2 / 1 |
| `POST .../versions/{vid}/restore` | 6 / 4 | 4 / 1 |
| `GET /diagrams/{id}/versions` (10 phiên bản) | 2 / 11 | 2 / 0 |
| `GET /diagrams/{id}/versions/{vid}` | 2 / 2 | 2 / 1 |
| `GET /diagrams`, `GET /diagrams?title=` (trang 20) | 4 / 21 | 1 / 0 |
| `GET /diagrams/public` | 4 | 1 |
| `POST /diagrams/{id}/shares` | 4 / 1 | 2 / 0 |
| `PATCH /diagrams/{id}/shares/{sid}` | 3 / 1 | 2 / 0 |
| `DELETE /diagrams/{id}/shares/{sid}` | 4 / 1 | 2 / 0 |
| `GET /diagrams/{id}/shares` | 2 / 1 | 2 / 0 |
| `DELETE /diagrams/{id}` | 3 / 2 | 2 / 0 |
| `POST /auth/register` | 4 | 3 |
| `POST /auth/login` | 3 | 2 |
| `POST /auth/refresh-token` | 5 | 4 |
| `POST /auth/logout` | 3 | 2 |

Đánh đổi:

- Có SQL viết tay trong hai adapter, khó đổi hệ CSDL hơn (`RETURNING`, `ILIKE`, `LATERAL`, `pg_trgm` là của PostgreSQL). Chấp nhận được: dự án chỉ dùng PostgreSQL, và SQL nằm gọn trong `infrastructure`, có test tích hợp trên PostgreSQL thật cho từng câu.
- Response của PUT trả `data` đúng như client gửi (JSON gọn), còn GET trả dạng text của `jsonb` (khóa đã sắp, có khoảng trắng). Hai dạng tương đương về nội dung; pha 1 cũng như vậy.
- Thêm ba index làm chậm ghi một chút (index trigram cập nhật khi đổi tên). Ghi chiếm 15% W2 và chủ yếu đổi `data`, không đổi `title`.
- Tinh chỉnh runtime (kích thước pool Hikari, virtual threads) chưa đổi mặc định: chỉ đổi khi số đo cho thấy lợi, mỗi lần một biến (ma trận `tune` trong `bench/kaggle/run_matrix.sh`).

## Kiểm chứng

- `./mvnw test`: toàn bộ test cũ và mới xanh, gồm `ArchitectureTest`, `DiagramVersionConcurrencyTest` (thêm: 20 luồng cùng sửa, lưu và khôi phục xen kẽ), `JpaRepositoriesIntegrationTest` (từng câu SQL trên PostgreSQL thật: phân trang ổn định khi trùng `updated_at`, tìm tên có ký tự đại diện, 409 và 404 của UPDATE có điều kiện, khôi phục phiên bản của sơ đồ khác không đổi gì, cascade khi xóa), `SqlPerRequestE2eTest` (bảng trên).
- Số đo trên Kaggle (phiên `b12`, C0 so với C1 cùng phiên, xen kẽ; chi tiết ở [kết quả](../pha-2/ket-qua.md) mục 3):

| Chỉ số | C0 | C1 |
|---|---|---|
| Sức chứa W2 (p95 ≤ 100 ms, lỗi < 1%) | 200 req/s | **800 req/s** (CPU của hệ thống được đo hết ở 900) |
| W2 200 req/s: p95 / CPU PostgreSQL / khối CSDL mỗi request | 100,8 ms / 1,12 nhân / 839 | **9,0 ms / 0,26 nhân / 101** |
| W1 (50 VU): throughput / p95 | 832 req/s / 150,5 ms | **1.191 req/s / 99,0 ms** |
| W3 20 req/s: p95 / SQL mỗi request / khối CSDL mỗi request | 156 ms (lần lặp không quá tải) / 2,19 / 857 | **97 ms / 1,52 / 133** |
| W3: tốc độ còn phục vụ kịp | 30 req/s | **70 req/s** |
| Danh sách công khai (EXPLAIN): câu lệnh / thời gian | 2 câu, quét tuần tự, 21 ms | 1 câu, index-only, 2,5 ms |

QA-P1 (sức chứa ≥ 2 lần C0) đạt, gấp 4. QA-P3 (danh sách, tìm kiếm p95 ≤ 50 ms, không đọc `data`) đạt: 7,6 / 25,7 / 15,0 ms ở 400 req/s. QA-P2 chỉ đạt một phần với riêng B1. Lượng dữ liệu của W3 giảm 84%, nhưng số câu lệnh mỗi request chỉ giảm 31% (mục tiêu 50%), vì mỗi lần mở sơ đồ vẫn cần một câu đọc nội dung; cộng B2 (cache) thì giảm 58%.
