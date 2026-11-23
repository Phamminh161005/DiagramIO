# Bộ đo hiệu năng (M0, pha 2)

Thư mục này dựng lại phép đo hiệu năng cho pha 2: cùng một máy (Kaggle Notebook CPU), cùng dữ liệu, cùng kịch bản, chạy lại được từ một notebook trắng. Lý do cho từng lựa chọn nằm trong [ADR-0001](../docs/adr/0001-phuong-phap-do.md); kết quả đánh giá hiện trạng nằm trong [danh-gia-hien-trang.md](../docs/pha-2/danh-gia-hien-trang.md).

```
bench/
├── kaggle/
│   ├── diagramio-bench.ipynb  notebook để tải lên Kaggle, gọi các script dưới đây
│   ├── setup.sh               cài PostgreSQL 16, JDK 21, k6, async-profiler; cấu hình PostgreSQL
│   ├── env.sh                 phiên bản cố định, thư mục, bố trí CPU, hàm dùng chung
│   ├── env_report.sh          ghi cấu hình máy và phần mềm của phiên đo (env.txt)
│   ├── build.sh               build jar của một ref git (tag, commit)
│   ├── prepare_data.sh        tạo CSDL mẫu: schema bằng Flyway của app, dữ liệu sinh xác định
│   ├── run_one.sh             một lần đo: reset CSDL, chạy app, thu số liệu, chạy k6
│   └── run_matrix.sh          chạy cả ma trận đo của một mốc (m0, quick) rồi tổng hợp báo cáo
├── seed/gen.py                sinh dữ liệu xác định theo seed (CSV cho COPY, fixture cho k6)
├── k6/                        kịch bản tải: w1.js (chuỗi của pha 1), w2.js (hỗn hợp đọc/ghi; W3 = W2 sơ đồ lớn)
├── collect/                   thu số liệu phía server: CPU theo tiến trình, Prometheus của app, thống kê PostgreSQL
├── analysis/report.py         gộp kết quả một phiên thành report.md, summary.csv, biểu đồ
└── results/                   kết quả các phiên đo (chỉ commit bản tóm tắt, xem cuối file)
```

Số câu SQL mỗi request không phụ thuộc phần cứng nên được đo bằng test, không cần Kaggle:

```bash
./mvnw test -Dtest=SqlPerRequestE2eTest     # cần Docker; bảng ghi ra target/bench/sql-per-request.md
```

## 1. Chạy trên Kaggle

1. Tạo notebook mới trên Kaggle, chọn **File → Import Notebook** và tải lên [kaggle/diagramio-bench.ipynb](kaggle/diagramio-bench.ipynb).
2. Trong **Settings**: Accelerator là **None** (CPU), bật **Internet** (cần để cài gói và clone repo).
3. Ở ô đầu tiên, đặt `REPO_URL` và `REF`: commit hoặc tag có thư mục `bench/` (sau khi merge M0). C0 được build từ `REF`.
4. Chạy ở chế độ nền để không phụ thuộc trình duyệt: **Save Version → Save & Run All (Commit)**. Ma trận `m0` mất khoảng 3,5 giờ (giới hạn của Kaggle là 12 giờ).
5. Khi xong, tải `results.tgz` trong tab **Output**, giải nén vào `bench/results/` của repo, rồi commit các file tóm tắt (mục 5).

Nên chạy `quick` trước (khoảng 10 phút) để chắc môi trường ổn, rồi mới chạy `m0`. Mỗi phiên tự ghi `env.txt` (CPU, cgroup, phiên bản phần mềm) vì máy Kaggle có thể khác nhau giữa các phiên; so sánh hai cấu hình thì chạy chúng trong **cùng một phiên** (run_matrix.sh tự xen kẽ).

## 2. Chạy thử trên máy (Docker)

Kaggle không chạy được Docker, nên các script cài trực tiếp lên máy. Để thử trên máy dev mà không cài gì vào máy, chạy chúng trong một container Ubuntu (giống Kaggle: root, không có systemd):

```bash
docker run -d --name diagramio-bench --shm-size=1g -v "$(pwd):/repo:ro" ubuntu:24.04 sleep infinity
docker exec diagramio-bench git config --global --add safe.directory '*'
docker exec -e PG_SHARED_BUFFERS=1GB -e PG_EFFECTIVE_CACHE=2GB diagramio-bench bash /repo/bench/kaggle/setup.sh
docker exec -e REF_C0=WORKTREE -e RESULTS_DIR=/tmp/results -e JVM_HEAP=1g -e PG_SHARED_BUFFERS=1GB \
  diagramio-bench bash /repo/bench/kaggle/run_matrix.sh quick
docker cp diagramio-bench:/tmp/results ./bench-results-local    # xem report.md trong thư mục phiên
```

- `REF_C0=WORKTREE` build từ cây làm việc hiện tại, kể cả thay đổi chưa commit; trên Kaggle luôn dùng tag hoặc commit.
- Trên Windows (Git Bash) đặt `export MSYS_NO_PATHCONV=1` trước các lệnh `docker exec`, nếu không đường dẫn `/repo/...` bị đổi thành đường dẫn Windows.
- Máy ít RAM thì giảm `PG_SHARED_BUFFERS` và `JVM_HEAP` như trên. Số liệu đo trên máy dev chỉ để thử bộ đo, không đưa vào báo cáo: pha 2 chỉ so sánh trên Kaggle.

## 3. Phép đo

| Kịch bản | Mô tả | Mô hình tải |
|---|---|---|
| W1 | Chuỗi 6 request của pha 1: tạo sơ đồ, sửa, lưu phiên bản, khôi phục, tạo link, mở link | Đóng: 10, 50, 100 VU không nghỉ, như pha 1 |
| W2 | Hỗn hợp 85% đọc / 15% ghi trên dữ liệu 50.000 sơ đồ, sơ đồ "nóng" theo phân bố Zipf | Mở: tốc độ đến cố định 200, 500, 800 req/s; và tăng dần từng bậc để tìm sức chứa |
| W3 | W2 chỉ trên sơ đồ 500 KB - 1,5 MB | Mở: 20, 50 req/s; và tìm sức chứa |

Tỉ lệ thao tác của W2 (đổi bằng biến `MIX`, JSON):

| Đọc (85%) | % | Ghi (15%) | % |
|---|---|---|---|
| Chủ mở sơ đồ | 25 | Sửa sơ đồ (PUT) | 8 |
| Chủ mở lại, gửi `If-None-Match` nếu có ETag | 10 | Lưu phiên bản | 3 |
| Mở link `GET /shares/{token}` | 12 | Khôi phục phiên bản | 2 |
| Đọc sơ đồ kèm `X-Share-Token` | 10 | Tạo link chia sẻ | 2 |
| Khách đọc sơ đồ công khai | 5 | | |
| Danh sách sơ đồ của tôi / tìm theo tên | 6 / 4 | | |
| Danh sách công khai | 5 | | |
| Lịch sử phiên bản / xem một phiên bản | 6 / 2 | | |

Quy ước chung (giải thích trong ADR-0001):

- Mỗi lần chạy: chép CSDL mẫu, nạp vào bộ đệm, checkpoint, khởi động JVM mới; warm-up 60 giây không tính, đo 180 giây; lặp 3 lần, báo cáo trung vị và min–max.
- Mô hình tải mở: mỗi iteration của k6 là đúng một request, nên tốc độ đến chính là request/giây; thời gian chậm không làm giảm tải gửi tới (không bị coordinated omission).
- Sức chứa: tăng 100 req/s mỗi bậc 30 giây (bỏ 6 giây đầu mỗi bậc); kết quả là bậc cuối cùng còn đạt **p95 ≤ 100 ms, lỗi < 1%, đạt ≥ 95% tốc độ yêu cầu**. Một bậc vượt p95 1 giây hoặc lỗi 5% thì dừng sớm.
- 3 vCPU cho hệ thống được đo (app, PostgreSQL), 1 vCPU cho k6 và bộ thu số liệu (`taskset`). Báo cáo cảnh báo khi k6 dùng quá 80% CPU của nó.
- W2, W3: mỗi VU chỉ ghi vào phân vùng sơ đồ của riêng nó, nên không có 409 giả tạo; tranh chấp thật để dành cho W4.

Biến môi trường hay dùng (đầy đủ trong `kaggle/env.sh`, `k6/lib/config.js`):

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `REF_C0` | `HEAD` | git ref build cấu hình C0 (cấu hình sau: `REF_C1`...) |
| `REPS`, `WARMUP`, `MEASURE` | 3, 60, 180 | số lần lặp, giây warm-up, giây đo |
| `SCALE` | 1.0 | quy mô dữ liệu (1.0 = 2.000 user, 50.000 sơ đồ, ~150.000 phiên bản) |
| `SUT_CPUS`, `LOADGEN_CPUS` | 3 CPU đầu, CPU thứ 4 | bố trí CPU |
| `JVM_HEAP` | `2g` | heap cố định (`-Xms` = `-Xmx`) |
| `PG_SHARED_BUFFERS` | `4GB` | bộ đệm PostgreSQL (CSDL mẫu ở scale 1 khoảng 1,1 GB) |

## 4. Số liệu thu được

Mỗi lần chạy có một thư mục `<cấu hình>_<kịch bản>_<mô hình><mức>_r<lần>/`:

| File | Nội dung |
|---|---|
| `meta.json` | commit, tham số, thời điểm, mã thoát của k6 |
| `k6-summary.json` | độ trễ, throughput, lỗi theo pha đo, theo thao tác, theo bậc tải |
| `procstat.csv` | CPU và bộ nhớ theo nhóm tiến trình (app, PostgreSQL, k6), steal, độ bận từng CPU, mỗi 5 giây |
| `prom.csv` | số liệu của app (`/actuator/prometheus` trên cổng quản trị 127.0.0.1:9090): thời gian xử lý theo endpoint, pool kết nối, luồng Tomcat, GC |
| `pg_stat_*.csv` | câu SQL tốn thời gian nhất, số transaction, khối đọc (kể cả khối TOAST chứa cột `data`), checkpoint |
| `flame.html` | flame graph CPU (chỉ lần chạy có `--profile`) |

`report.py` gộp chúng thành `report.md` (bảng, biểu đồ, kiểm tra tính hợp lệ) và `summary.csv` (một dòng mỗi lần chạy).

## 5. Lưu kết quả vào repo

Commit thư mục phiên trong `bench/results/` gồm `report.md`, `summary.csv`, `charts/`, `env.txt`, `manifest.json`, `configs.txt` và các file nhỏ của từng lần chạy. Log thô và `prom.csv` (lớn) bị `bench/results/.gitignore` loại ra; mọi con số trong báo cáo đã có trong `summary.csv`.

## 6. Thêm một cấu hình mới (từ M1)

1. Gắn tag cho bản cải tiến (ví dụ `v2.0-m1`).
2. Trong `kaggle/run_matrix.sh`, thêm ma trận mới với `CONFIGS=(C0 C1)` và kịch bản cần so sánh.
3. Chạy với `REF_C0=v2.0-m0 REF_C1=v2.0-m1`. Hai cấu hình chạy xen kẽ trong cùng phiên; nếu C1 có migration mới, Flyway tự chạy khi app khởi động trên bản chép của CSDL mẫu.
4. Cải tiến nào đổi số câu SQL thì sửa con số trong `SqlPerRequestE2eTest` cùng PR.
