# Đánh giá hiện trạng (M0)

| | |
|---|---|
| Trạng thái | Xong. Số liệu C0 đo trên Kaggle ngày 24/11/2026, phiên [`20261124-061459-m0`](../../bench/results/20261124-061459-m0/report.md) (mục 6) |
| Mã được đánh giá | C0 = mã pha 1 cộng phần đo (Actuator, Micrometer), không đổi hành vi nghiệp vụ |
| Phương pháp | [ADR-0001](../adr/0001-phuong-phap-do.md); cách chạy lại: [bench/README.md](../../bench/README.md) |
| Kế hoạch | [ke-hoach-pha-2.md](../ke-hoach-pha-2.md), mốc M0 |

## 1. Tóm tắt

- Bộ đo trong `bench/` đã chạy trọn quy trình từ máy trắng (container Ubuntu 24.04 và 22.04, giống Kaggle: root, không có systemd): cài đặt, sinh và nạp 50.000 sơ đồ, đo W1, W2, W3 ở cả ba mô hình tải, thu số liệu phía server, flame graph, tổng hợp báo cáo.
- Số câu SQL, transaction và số lần chở cột `data` của từng endpoint được đo chính xác bằng test `SqlPerRequestE2eTest` (mục 3). Kết quả **xác nhận đúng toàn bộ bảng giả thuyết ở mục 2.3 của kế hoạch** và thêm năm phát hiện.
- Trên Kaggle (mục 6), W2 giữ được SLO tới **300 req/s** rồi bão hòa ở khoảng **400 req/s**: 3 CPU của hệ thống được đo bận hết, **PostgreSQL dùng nhiều CPU hơn app** (1,73 so với 1,25 nhân), và **câu đếm sơ đồ công khai chiếm 46% thời gian CSDL** dù chỉ 5% request gọi tới, đúng như chẩn đoán trên máy dev (mục 5). W3 (sơ đồ 0,5-1,5 MB) chỉ giữ SLO tới 10-30 req/s; đọc sơ đồ theo id chiếm 57% thời gian CSDL (92 ms mỗi lần).
- Flame graph cho thêm hai phát hiện: ở W3, **83% CPU của app là Jackson chép cột `data` vào response** từng ký tự; ở W2, gần **8% CPU là chi phí của chính phần đo** (Spring Security quan sát từng filter). Phát hiện thứ nhất được đưa vào B1, phát hiện thứ hai được tắt cho mọi cấu hình trong bộ đo.
- Thứ tự ưu tiên ở mục 7 được số liệu Kaggle xác nhận: B1 trước (danh sách công khai, không chở `data` khi không cần, ghi response nhanh), rồi B2 (đường đọc theo id và sơ đồ lớn).

## 2. Bộ đo

| Hạng mục của M0 | Kết quả |
|---|---|
| `setup.sh` chạy từ máy trắng | `bench/kaggle/setup.sh`: PostgreSQL 16 (PGDG), JDK Temurin 21.0.13.1, k6 v2.3.0, async-profiler 4.6, kiểm tra SHA-256; tự nhận bản Ubuntu/Debian |
| Dữ liệu xác định, CSDL mẫu, reset mỗi lần chạy | `bench/seed/gen.py` (giống từng byte giữa Python 3.10, 3.12 và Linux; ~60 giây ở scale 1); `prepare_data.sh` nạp trong ~2 phút, CSDL 1,08 GB; mỗi lần chạy chép bằng `FILE_COPY`, nạp bộ đệm, checkpoint |
| Kịch bản k6 | W1 (mô hình đóng như pha 1), W2 và W3 (mô hình mở: tốc độ cố định và tìm sức chứa); mỗi iteration là một request, ghi theo phân vùng nên không có 409 giả tạo |
| Số liệu phía server | Actuator + Prometheus trên cổng quản trị chỉ nghe localhost; `procstat.py` (CPU từng nhóm tiến trình, steal, từng CPU); `prom_scrape.py`; `pg_stats.sh` (pg_stat_statements, khối TOAST, checkpoint) |
| Số câu SQL mỗi request | `SqlPerRequestE2eTest`, đối chiếu với tầng CSDL trong mỗi lần đo (W1: 3,34 câu/request ở `pg_stat_statements`, khớp 20 câu / 6 request của test) |
| Flame graph | async-profiler 60 giây giữa pha đo, trong lần chạy riêng |
| Báo cáo | `bench/analysis/report.py`: bảng, biểu đồ, kiểm tra tính hợp lệ (k6 không bão hòa, steal, đạt tốc độ yêu cầu, không 409 giả tạo, không checkpoint giữa lần đo) |
| Locust W1 của pha 1 | Không làm: kịch bản gốc chỉ có trong notebook Kaggle của pha 1, không đọc được ngoài Kaggle, nên không thể chạy lại "đúng như pha 1". W1 của k6 dựng lại cùng chuỗi request và cùng mức tải |

## 3. Số câu SQL mỗi request

Đo bằng `SqlPerRequestE2eTest` (PostgreSQL 16 thật, DataSource được bọc để ghi mọi câu lệnh). Sơ đồ đo có data 50 KB; danh sách đo trên một trang đầy 20 sơ đồ. Số liệu này là của mã nguồn, không phụ thuộc máy; test giữ chúng làm mốc, cải tiến nào làm đổi thì sửa test cùng PR.

| Endpoint | Người gọi | Ghi chú | SQL | SELECT | INSERT | UPDATE | DELETE | Commit | Kết nối | KB đọc | KB ghi | Lần chở data |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `GET /health` | khách | | 1 | 1 | 0 | 0 | 0 | 0 | 1 | 0,0 | 0,0 | 0 |
| `POST /auth/register` | khách | | 4 | 3 | 1 | 0 | 0 | 1 | 3 | 0,0 | 0,2 | 0 |
| `POST /auth/login` | khách | | 3 | 2 | 1 | 0 | 0 | 1 | 2 | 0,1 | 0,1 | 0 |
| `POST /auth/refresh-token` | khách | | 5 | 3 | 1 | 1 | 0 | 1 | 1 | 0,2 | 0,3 | 0 |
| `POST /auth/logout` | chủ tài khoản | | 3 | 2 | 0 | 1 | 0 | 1 | 2 | 0,2 | 0,2 | 0 |
| `GET /users/me` | chủ tài khoản | | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 0,1 | 0,0 | 0 |
| `PATCH /users/me/password` | chủ tài khoản | | 3 | 2 | 0 | 1 | 0 | 2 | 2 | 0,2 | 0,1 | 0 |
| `POST /diagrams` | chủ sơ đồ | | 1 | 0 | 1 | 0 | 0 | 1 | 1 | 0,0 | 0,1 | 0 |
| `GET /diagrams/{id}` | chủ sơ đồ | | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 55,0 | 0,0 | 1 |
| `GET /diagrams/{id}` | link view | X-Share-Token | 2 | 2 | 0 | 0 | 0 | 1 | 2 | 55,1 | 0,1 | 1 |
| `GET /diagrams/{id}` | khách | sơ đồ công khai | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 55,1 | 0,0 | 1 |
| `PUT /diagrams/{id}` | chủ sơ đồ | | 3 | 2 | 0 | 1 | 0 | 2 | 2 | 110,1 | 49,2 | 3 |
| `PUT /diagrams/{id}` | link edit | X-Share-Token | 4 | 3 | 0 | 1 | 0 | 2 | 3 | 110,2 | 49,2 | 3 |
| `PUT /diagrams/{id}` | chủ sơ đồ | version cũ, 409 | 1 | 1 | 0 | 0 | 0 | 1 | 1 | 55,0 | 0,0 | 1 |
| `GET /diagrams/{id}/versions` | chủ sơ đồ | 10 phiên bản | 2 | 2 | 0 | 0 | 0 | 1 | 2 | 605,4 | 0,0 | 11 |
| `GET /diagrams/{id}/versions/{vid}` | chủ sơ đồ | | 2 | 2 | 0 | 0 | 0 | 1 | 2 | 110,1 | 0,0 | 2 |
| `POST /diagrams/{id}/versions` | chủ sơ đồ | | 4 | 3 | 1 | 0 | 0 | 1 | 1 | 55,0 | 55,1 | 2 |
| `POST .../versions/{vid}/restore` | chủ sơ đồ | | 6 | 4 | 1 | 1 | 0 | 1 | 1 | 110,1 | 110,2 | 4 |
| `GET /diagrams/{id}/shares` | chủ sơ đồ | | 2 | 2 | 0 | 0 | 0 | 1 | 2 | 55,2 | 0,0 | 1 |
| `POST /diagrams/{id}/shares` | chủ sơ đồ | | 4 | 3 | 1 | 0 | 0 | 2 | 3 | 55,0 | 0,2 | 1 |
| `PATCH /diagrams/{id}/shares/{sid}` | chủ sơ đồ | | 3 | 2 | 0 | 1 | 0 | 3 | 3 | 55,1 | 0,1 | 1 |
| `GET /shares/{token}` | khách | | 2 | 2 | 0 | 0 | 0 | 1 | 2 | 55,1 | 0,1 | 1 |
| `DELETE /diagrams/{id}/shares/{sid}` | chủ sơ đồ | | 4 | 3 | 0 | 0 | 1 | 3 | 3 | 55,2 | 0,1 | 1 |
| `GET /diagrams` | chủ sơ đồ | trang 20 sơ đồ | 4 | 4 | 0 | 0 | 0 | 0 | 4 | 1156,2 | 0,1 | 21 |
| `GET /diagrams?title=` | chủ sơ đồ | 20 sơ đồ khớp | 4 | 4 | 0 | 0 | 0 | 0 | 4 | 1156,2 | 0,1 | 21 |
| `GET /diagrams/public` | khách | | 4 | 4 | 0 | 0 | 0 | 0 | 4 | | | |
| `DELETE /diagrams/{id}` | chủ sơ đồ | | 3 | 2 | 0 | 0 | 1 | 2 | 2 | 110,1 | 0,1 | 2 |

Commit là số transaction tường minh; Kết nối là số lần mượn kết nối từ pool; "Lần chở data" quy KB đọc và ghi ra số lần chở cột `data` (55 KB khi đọc từ CSDL). Danh sách công khai được đo với `size=1` vì CSDL test dùng chung nên không ghi số byte; số câu lệnh giống trang đầy. Bản đầy đủ kèm từng câu SQL: chạy test rồi xem `target/bench/sql-per-request.md`.

**Xác nhận giả thuyết ở mục 2.3 của kế hoạch:** sửa sơ đồ 3 câu và chở `data` 3 lần (kiểm tra quyền đọc cả sơ đồ, `merge` đọc lại, UPDATE); lưu phiên bản 4 câu, 2 lần; khôi phục 6 câu, 4 lần; tạo link 4 câu (đọc cả `data` chỉ để biết ai là chủ, thêm một SELECT do `merge`); `X-Share-Token` thêm 1 SELECT ở mọi request; danh sách 4 câu và chở `data` của mọi sơ đồ trong trang (21 lần cho trang 20 sơ đồ, vì lần đếm thứ hai lấy thêm một dòng); lịch sử chở `data` của mọi phiên bản dù API chỉ trả tóm tắt.

**Phát hiện thêm:**

1. Query method tự sinh của Spring Data (`findByX`, `existsByX`, gồm cả các câu lấy trang và đếm) không có `@Transactional`, nên chạy autocommit: mỗi câu mượn một kết nối riêng (`GET /diagrams` mượn pool 4 lần), và câu lấy trang với câu đếm không cùng một snapshot, nên khi có ghi đồng thời tổng số có thể lệch với nội dung trang.
2. `DELETE /diagrams/{id}` đọc `data` hai lần chỉ để xóa; đổi quyền và thu hồi link đọc cả `data` của sơ đồ chỉ để kiểm tra chủ.
3. Xem một phiên bản chở hai lần `data` (cả sơ đồ hiện tại lẫn phiên bản).
4. Dạng text của `jsonb` đọc ra dài hơn khoảng 10% so với JSON gọn gửi lên (55 KB so với 49 KB), vì PostgreSQL chuẩn hóa khoảng trắng; liên quan tới thử nghiệm `json` hay `jsonb` ở B1.
5. Đổi mật khẩu ghi lại mọi cột của `users`, không chỉ `password_hash` (Hibernate cập nhật toàn bộ cột); ít ảnh hưởng hiệu năng nhưng là dấu hiệu chung của cách lưu bằng `save()` toàn entity.

## 4. Kế hoạch thực thi trên dữ liệu chuẩn

`EXPLAIN (ANALYZE, BUFFERS)` trên CSDL của bộ đo (scale 1: 50.000 sơ đồ, ~150.000 phiên bản), PostgreSQL 16 với cùng cấu hình như trên Kaggle. Hình dạng kế hoạch là thứ chuyển được sang Kaggle; thời gian đo trên máy dev chỉ để so tương đối giữa các câu. PostgreSQL 16 không có `EXPLAIN (SERIALIZE)`, nên chi phí giải nén và chuyển `data` sang text được đo riêng bằng cách tính `length(data::text)` trên cùng tập dòng.

| Câu truy vấn (sinh bởi Hibernate) | Kế hoạch | Dòng quét | Thời gian | Nhận xét |
|---|---|---|---|---|
| Đếm sơ đồ công khai (`where is_public`) | Seq Scan | 50.000, loại 44.888 | 40,8 ms | Không có index; chạy 2 lần mỗi `GET /diagrams/public` |
| Trang công khai thứ 10 | Seq Scan + Limit | 1.972 để lấy 200 | 0,6 ms | Không `ORDER BY`: thứ tự giữa các trang không ổn định; trang càng sâu càng quét nhiều |
| Trang đầu sơ đồ của user nhiều sơ đồ nhất (4.248 sơ đồ) | Seq Scan + Limit | 296 | 0,09 ms | Rẻ vì không `ORDER BY`; thêm `ORDER BY updated_at` thì cần index `(owner_id, updated_at)` |
| ... cùng trang, kèm giải nén và chuyển `data` của 20 dòng | | | 1,66 ms | Chở `data` chiếm khoảng 95% chi phí của câu lấy trang |
| Đếm sơ đồ của user đó | Bitmap Index Scan trên `idx_diagrams_owner` | 4.248 | 4,0 ms | Chạy 2 lần mỗi request danh sách |
| Tìm theo tên trong sơ đồ của user đó | Bitmap theo owner, lọc `upper(title) like` | 356 để lấy 20 | 1,0 ms | Lọc tuần tự trong sơ đồ của user; user càng nhiều sơ đồ càng chậm |
| Link của một sơ đồ (`diagram_shares where diagram_id`) | Seq Scan | 21.577 | 2,2 ms | Thiếu index trên khóa ngoại `diagram_id` |
| Lịch sử của sơ đồ 389 phiên bản | Bitmap Index Scan + Sort | 389 | 2,7 ms | |
| ... cùng lịch sử, kèm giải nén `data` của mọi phiên bản | | | 9,9 ms | API chỉ trả tóm tắt nhưng vẫn phải trả chi phí này |

## 5. Chẩn đoán trên máy dev (không phải kết quả)

Để thử bộ đo, W2 ở chế độ tìm sức chứa được chạy một lần trên dữ liệu đầy đủ, trong container trên máy dev (Intel i5-11400H, 3 CPU cho hệ thống được đo, 1 CPU cho k6). Các con số dưới đây **không dùng để so sánh** (pha 2 chỉ so trên Kaggle); chúng được ghi lại vì hai điều sau ít phụ thuộc máy: tài nguyên nào bão hòa trước, và câu SQL nào chiếm thời gian của CSDL.

- p95 khoảng 25 ms tới 500 req/s, 89 ms ở 700 req/s, vượt 1 giây ở 800 req/s (k6 tự dừng). Ở bậc cuối, cả 3 CPU của hệ thống được đo bận 100%: **PostgreSQL dùng 2,05 nhân, app 0,92 nhân**. k6 dùng 30-45% CPU của nó, nên máy tạo tải không phải nút thắt. Mỗi request trung bình 2,17 câu SQL.
- Phân bố thời gian thực thi của PostgreSQL theo câu lệnh (`pg_stat_statements`, cả lần chạy):

| % thời gian CSDL | Câu lệnh | Thời gian trung bình | Ghi chú |
|---|---|---|---|
| 49,4% | Đếm sơ đồ công khai | 22,5 ms | 5% request (`list_public`), mỗi request đếm 2 lần, quét tuần tự |
| 13,2% | Trang sơ đồ của tôi | 4,9 ms | Chở `data` của 20 sơ đồ |
| 12,1% | Tìm theo tên (trang và đếm) | 3,3-4,3 ms | |
| 7,5% | Đọc một sơ đồ theo id | 0,36 ms | Câu gọi nhiều nhất (69.706 lần): mở sơ đồ, kiểm tra quyền |
| 7,3% | Trang công khai có `offset` | 9,1 ms | |
| 3,8% | Đếm sơ đồ của tôi | 1,4 ms | |

Bảng `diagrams` bị quét tuần tự 14.889 lần, đọc tổng cộng 374 triệu dòng trong khoảng 3 phút chạy. Tỉ lệ trên phụ thuộc vào tỉ lệ thao tác của W2 (danh sách công khai 5%) và quy mô dữ liệu, nên phải xác nhận lại trên Kaggle.

## 6. Kết quả C0 trên Kaggle

Phiên [`20261124-061459-m0`](../../bench/results/20261124-061459-m0/report.md): Kaggle Notebook CPU, **AMD EPYC 7B12**, 4 vCPU (2 nhân × 2 luồng), 31 GB RAM, Ubuntu 24.04; mã `v2.0-m0`; dữ liệu scale 1 (50.000 sơ đồ). Mỗi phép đo lặp 3 lần, ghi trung vị (min–max); ba lần lặp của mọi phép đo dưới ngưỡng tải đều rất sát nhau.

| Kịch bản | Chỉ số | C0 |
|---|---|---|
| W2 | Sức chứa đạt SLO (p95 ≤ 100 ms, lỗi < 1%) | **300 req/s** (300–400) |
| W2 | p95 ở 200 req/s | 31,6 ms (31,5–33,3) |
| W2 | 500 / 800 req/s | vượt sức chứa: chỉ đạt ~400 req/s, p95 ~3,7 giây |
| W2 | CPU app / PostgreSQL / k6 ở 200 req/s | 0,40 / 0,74 / 0,14 nhân |
| W2 | CPU app / PostgreSQL / k6 khi bão hòa (500 req/s) | 1,25 / 1,73 / 0,34 nhân |
| W3 | Sức chứa đạt SLO | **10 req/s** (10–30) |
| W3 | p95 ở 20 / 50 req/s | 90,9 ms / 141,9 ms (một lần lặp ở 50 req/s bị nghẽn) |
| W1 | Throughput và p95 ở 10 VU | 1.503 req/s, 10,8 ms |
| W1 | Throughput và p95 ở 50 / 100 VU | 1.286 req/s, 93,8 ms / 1.253 req/s, 208,2 ms |
| Tầng CSDL | SQL mỗi request: W1 / W2 / W3 | 3,33 / 2,15 / 2,13 (W1 khớp test đặc tả: 20 câu / 6 request) |

**Vì sao:**

- **W2 bị giới hạn bởi PostgreSQL.** Ngay ở 200 req/s, PostgreSQL đã dùng gần gấp đôi CPU của app. Khi bão hòa, cả 200 luồng Tomcat bận và tới 191 request xếp hàng chờ 10 kết nối của pool: thời gian chờ nằm ở CSDL chứ không ở JVM. Theo `pg_stat_statements` (lần chạy 500 req/s): đếm sơ đồ công khai 46% thời gian CSDL (64 ms mỗi lần, quét tuần tự 50.000 dòng, 2 lần mỗi request), trang sơ đồ của tôi 16%, tìm theo tên 15%, trang công khai có `offset` 9%, đọc theo id 7%. Các con số này trùng với chẩn đoán trên máy dev ở mục 5.
- **W3 bị giới hạn bởi việc đọc và trả sơ đồ lớn.** Đọc một sơ đồ theo id chiếm 57% thời gian CSDL (92 ms mỗi lần: giải nén TOAST, chuyển `jsonb` sang text). Ở phía app, flame graph cho thấy **83% CPU nằm ở `UTF8JsonGenerator._writeRawSegment`**: `@JsonRawValue` buộc Jackson chép chuỗi `data` (~1 MB) vào response từng ký tự, sau khi đã dãn nó ra `char[]`.
- **W1 bị giới hạn bởi JVM** (app 1,9 nhân, PostgreSQL 0,85 nhân): chuỗi tạo, sửa, lưu, khôi phục, tạo link với dữ liệu nhỏ. Flame graph W2 cũng cho thấy chi phí chung của app: Hibernate 16%, JDBC 11%, Jackson 8,5%, JIT 18%.
- **Chi phí của chính phần đo:** gần 8% CPU của app ở W2 nằm trong Micrometer, do Spring Security quan sát từng filter và mỗi lần cập nhật một `LongTaskTimer` (danh sách O(n) theo số request đang chạy). Từ các lần đo sau, bộ đo tắt hai loại quan sát này cho mọi cấu hình (kể cả C0), giữ nguyên số liệu `http.server.requests`; ứng dụng cũng tắt chúng trong `application.yml` từ B1.
- **So với pha 1:** cùng chuỗi request ở 10 người dùng, Locust của pha 1 chỉ đẩy được 330 req/s còn k6 đẩy được 1.503 req/s. Điều này khẳng định nhận định ở mục 2.2 của kế hoạch: số liệu pha 1 bị giới hạn bởi Locust (máy, dữ liệu và bố trí CPU cũng khác, nên không so trực tiếp từng con số).

Kiểm tra tính hợp lệ: các lần W2 ở 500 và 800 req/s được đánh dấu "vượt sức chứa". Đó là kết quả (điểm bão hòa) chứ không phải lỗi bộ đo, nhưng độ trễ của chúng chỉ để tham khảo. Ở W3, bộ đo của phiên này có hai khiếm khuyết đã được sửa cho các phiên sau:

- 227 sơ đồ lớn có lịch sử được chia cho 300 phân vùng, nên một số VU không có gì để khôi phục và phải đổi sang đọc (47-177 thao tác mỗi lần, dưới 2%). Giờ sơ đồ có lịch sử được chia đều trước, và W3 dùng tối đa 200 VU.
- Ở lần W3 50 req/s bị nghẽn, request timeout làm VU nhớ sai version rồi nhận 409. Giờ sau một lần ghi không rõ kết quả, VU đọc lại version trước khi ghi tiếp.

Lưu ý: lần `quick` trước đó chạy trên Intel Xeon 2,2 GHz, còn phiên này chạy trên AMD EPYC 7B12. Máy Kaggle đổi giữa các phiên, vì vậy mọi phép so sánh C0 với bản cải tiến phải chạy xen kẽ trong cùng một phiên (ADR-0001).

## 7. Nút thắt và thứ tự ưu tiên đề xuất

Từ mục 3-6, giữ B1 là việc đầu tiên. Thứ tự các mục con theo lợi ích đo được (đã được số liệu Kaggle xác nhận):

1. **Danh sách công khai:** index một phần `WHERE is_public` kèm `ORDER BY`, đếm tổng một lần (controller đang gọi service hai lần). Mục này nhắm vào câu chiếm khoảng một nửa thời gian CSDL của W2.
2. **Không chở `data` khi không cần:** truy vấn danh sách và lịch sử chỉ lấy cột tóm tắt; kiểm tra quyền bằng `findAccessInfo` (4 cột) cho sửa, xóa, link, phiên bản. Chở `data` chiếm phần lớn chi phí của các câu này (mục 4).
3. **Ghi gọn:** UPDATE có điều kiện cho PUT, chép phiên bản ngay trong CSDL cho lưu và khôi phục, `Persistable` để bỏ SELECT của `merge`.
4. **Index còn thiếu:** `diagram_shares(diagram_id)`, `diagrams(owner_id, updated_at)`, `pg_trgm` cho tìm theo tên (mục 2.3 của kế hoạch).
5. **Lấy trang và đếm trong một câu lệnh** (phát hiện 1 ở mục 3): một kết nối, cùng một snapshot, thay cho 4 câu chạy autocommit trên 4 kết nối.
6. **Ghi `data` vào response bằng byte** (mục 6): bỏ đường chép từng ký tự của `@JsonRawValue`, nhắm vào 83% CPU của app ở W3.

Sau B1, đường đọc theo id (câu gọi nhiều nhất) và tra token chia sẻ vẫn chạm CSDL ở mọi request: đó là phần của B2 và B3. Các bài toán còn lại giữ thứ tự của kế hoạch.

## 8. Giới hạn

- Số đo thời gian ở mục 4 và 5 là của máy dev, chỉ để chẩn đoán; số liệu chính thức là mục 6.
- W1 dựng lại theo README vì kịch bản Locust gốc không có trong repo; không so sánh chéo k6 với Locust.
- Hệ thống được đo có 3 vCPU; trên Kaggle (2 nhân × 2 luồng) k6 vẫn chia một nhân vật lý với hệ thống được đo. k6 dùng tối đa 0,5 nhân, dưới ngưỡng 0,8 của kiểm tra hợp lệ.
- Kết nối JDBC trên Kaggle được mã hóa TLS (gói PostgreSQL của Ubuntu bật `ssl` mặc định, JDBC mặc định `sslmode=prefer`); chi phí mã hóa dưới 2% CPU của app và giống nhau ở mọi cấu hình.
- `docs/openapi.yaml` trong repo đã lệch so với mã từ trước M0 (`DiagramVersionResponse` thiếu `id` và có trường `diagramType` lạc chỗ; `ChangePasswordRequest` có `maxLength` không có trong DTO). Đã sinh lại; đặc tả sinh từ mã trước và sau M0 giống hệt nhau, nên M0 không đổi hợp đồng API.
