# Kiểm thử tải pha 1 (Locust trên Kaggle)

> Báo cáo kiểm thử tải của pha 1, chuyển nguyên văn từ README. Số liệu chỉ để tham khảo, **không so trực tiếp với pha 2**: Locust, app và PostgreSQL dùng chung 4 vCPU, kịch bản 100% ghi trên CSDL gần rỗng, mô hình tải đóng không thời gian nghỉ (phân tích ở mục 2.2 của [kế hoạch pha 2](../ke-hoach-pha-2.md)). Pha 2 đo lại bằng bộ đo mới trong [bench/](../../bench/README.md), phương pháp ở [ADR-0001](../adr/0001-phuong-phap-do.md).

Link notebook: https://www.kaggle.com/code/minhwayne/diagramio

Toàn bộ hệ thống gồm ứng dụng Spring Boot (Java 21), CSDL PostgreSQL 16 và công cụ Locust (headless) được khởi chạy trực tiếp trên cùng một Kaggle Notebook (`localhost:8080`). Việc kiểm thử trên `localhost` giúp loại bỏ hoàn toàn độ trễ đường truyền Internet và đo lường chính xác hiệu năng thực tế của Backend. Kết quả dưới đây là mốc baseline; pha 2 cần chạy lại cùng cấu hình và kịch bản để so sánh.

| Thông số | Giá trị |
|---|---|
| Máy | Kaggle Notebook CPU: 4 vCPU, 31 GB RAM |
| Phần mềm | Java 21, PostgreSQL 16, Locust (Python 3.13) |
| Mã nguồn được đo | commit `ca41f27` (trước khi merge PR #14) |
| Dữ liệu ban đầu | Pre-seed sẵn 100 tài khoản cố định vào CSDL trước khi đo |
| Kịch bản | Mỗi Virtual User đăng nhập 1 lần duy nhất lúc khởi tạo (`on_start`), sau đó chạy lặp liên tục chuỗi 5 bước nghiệp vụ (Tạo sơ đồ → Sửa & Snapshot → Khôi phục → Tạo link chia sẻ → Truy cập link chia sẻ) cho đến hết thời gian đo. |

### Cách chạy trên Kaggle Notebook:
1. Cài đặt JDK 21, PostgreSQL 16 và Locust (`pip install locust`).
2. Chuyển cơ chế xác thực PostgreSQL sang `trust` cho `localhost` và tạo database `diagramio`.
3. Clone repository, build ứng dụng bằng `./mvnw clean package -DskipTests` và chạy `java -jar target/diagramio-0.0.1-SNAPSHOT.jar &` (Flyway tự động tạo bảng).
4. Khởi tạo dữ liệu mẫu (Pre-seed 100 users) và thực thi script Locust đo tải ở các mức 10, 50, 100 users.

---

### Kết quả kiểm thử chi tiết theo từng mức tải

#### Mức tải 10 Users (Tải nhẹ)
* **Tổng RPS:** 329.78 requests/s | **Tổng Tỉ lệ lỗi:** 0.0% | **p95 Tổng:** 7 ms | **p99 Tổng:** 9 ms

| Phương thức & Endpoint | RPS | p95 (ms) | p99 (ms) | Tỉ lệ lỗi |
|---|---|---|---|---|
| POST /api/v1/auth/login *(on_start)* | 0.06 | 320 | 320 | 0.0% |
| POST /api/v1/diagrams | 54.98 | 6 | 8 | 0.0% |
| PUT /api/v1/diagrams/[id] | 54.97 | 7 | 9 | 0.0% |
| POST /api/v1/diagrams/[id]/versions | 54.97 | 7 | 9 | 0.0% |
| POST /api/v1/diagrams/[id]/versions/[version_id]/restore | 54.95 | 8 | 10 | 0.0% |
| POST /api/v1/diagrams/[id]/shares | 54.93 | 7 | 9 | 0.0% |
| GET /api/v1/shares/[token] | 54.93 | 5 | 6 | 0.0% |

---

#### Mức tải 50 Users (Tải trung bình)
* **Tổng RPS:** 736.22 requests/s | **Tổng Tỉ lệ lỗi:** 0.0% | **p95 Tổng:** 46 ms | **p99 Tổng:** 56 ms

| Phương thức & Endpoint | RPS | p95 (ms) | p99 (ms) | Tỉ lệ lỗi |
|---|---|---|---|---|
| POST /api/v1/auth/login *(on_start)* | 0.28 | 490 | 500 | 0.0% |
| POST /api/v1/diagrams | 122.78 | 46 | 56 | 0.0% |
| PUT /api/v1/diagrams/[id] | 122.72 | 47 | 57 | 0.0% |
| POST /api/v1/diagrams/[id]/versions | 122.70 | 40 | 50 | 0.0% |
| POST /api/v1/diagrams/[id]/versions/[version_id]/restore | 122.64 | 46 | 58 | 0.0% |
| POST /api/v1/diagrams/[id]/shares | 122.58 | 46 | 58 | 0.0% |
| GET /api/v1/shares/[token] | 122.53 | 44 | 53 | 0.0% |

---

#### Mức tải 100 Users (Tải cao)
* **Tổng RPS:** 747.49 requests/s | **Tổng Tỉ lệ lỗi:** 0.0% | **p95 Tổng:** 110 ms | **p99 Tổng:** 130 ms

| Phương thức & Endpoint | RPS | p95 (ms) | p99 (ms) | Tỉ lệ lỗi |
|---|---|---|---|---|
| POST /api/v1/auth/login *(on_start)* | 0.56 | 590 | 660 | 0.0% |
| POST /api/v1/diagrams | 124.68 | 110 | 130 | 0.0% |
| PUT /api/v1/diagrams/[id] | 124.59 | 110 | 130 | 0.0% |
| POST /api/v1/diagrams/[id]/versions | 124.54 | 100 | 120 | 0.0% |
| POST /api/v1/diagrams/[id]/versions/[version_id]/restore | 124.48 | 110 | 130 | 0.0% |
| POST /api/v1/diagrams/[id]/shares | 124.38 | 110 | 130 | 0.0% |
| GET /api/v1/shares/[token] | 124.26 | 110 | 120 | 0.0% |

---

### Nhận xét & Đánh giá hiệu năng Baseline
1. **Thông lượng tối đa (Maximum Throughput):** Sau khi giải phóng được điểm nghẽn BCrypt login lặp lại, thông lượng hệ thống đạt mốc ngưỡng giới hạn ~747 - 750 RPS trên cấu hình 4 vCPU của Kaggle.
2. **Độ trễ:** 
   * Ở mức 10 users: Độ trễ phản hồi thấp, p95 chỉ từ 5ms – 8ms.
   * Ở mức 50 users: Độ trễ giữ ổn định ở mốc 40ms – 47ms.
   * Ở mức 100 users: Tổng RPS bắt đầu chạm trần bão hòa (~747 req/s), độ trễ p95 tăng lên 100ms – 110ms nhưng hệ thống vẫn phản hồi dưới 0.15s.
3. **Độ tin cậy:** Tỉ lệ lỗi ghi nhận 0.0% trên toàn bộ 3 mức tải, khẳng định tính toàn vẹn dữ liệu và khả năng quản lý connection pool của Spring Boot + PostgreSQL vận hành rất chính xác.
