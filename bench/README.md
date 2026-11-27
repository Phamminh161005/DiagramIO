# Bộ đo hiệu năng (pha 2)

Thư mục này dựng lại phép đo hiệu năng cho pha 2: cùng một máy (Kaggle Notebook CPU), cùng dữ liệu, cùng kịch bản, chạy lại được từ một notebook trắng. Lý do cho từng lựa chọn nằm trong [ADR-0001](../docs/adr/0001-phuong-phap-do.md); kết quả đánh giá hiện trạng nằm trong [danh-gia-hien-trang.md](../docs/pha-2/danh-gia-hien-trang.md).

```
bench/
├── kaggle/
│   ├── diagramio-bench.ipynb  notebook để tải lên Kaggle, gọi các script dưới đây
│   ├── setup.sh               cài PostgreSQL 16, Redis 7.4, JDK 21, k6, async-profiler; cấu hình PostgreSQL
│   ├── env.sh                 phiên bản cố định, thư mục, bố trí CPU, hàm dùng chung
│   ├── env_report.sh          ghi cấu hình máy và phần mềm của phiên đo (env.txt)
│   ├── build.sh               build jar của một ref git (tag, commit)
│   ├── prepare_data.sh        tạo CSDL mẫu: schema bằng Flyway của app, dữ liệu sinh xác định
│   ├── run_one.sh             một lần đo: reset CSDL và Redis, chạy app, thu số liệu, chạy k6
│   ├── explain.sh             EXPLAIN (ANALYZE, BUFFERS) các truy vấn danh sách, tìm kiếm, link, lịch sử
│   └── run_matrix.sh          chạy cả một ma trận đo (b12, b3, tune, m0, quick) rồi tổng hợp báo cáo
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
3. Ở ô đầu tiên, đặt `REPO_URL`, `REF` (commit hoặc tag có thư mục `bench/`), `MATRIX` và `REPS`.
4. Chạy ở chế độ nền để không phụ thuộc trình duyệt: **Save Version → Save & Run All (Commit)**. Thời gian (giới hạn của Kaggle là 12 giờ):
   - `b12`: C0 (`v2.0-m0`), C1 (B1, `v2.1-b1`), C2 (B2, cache `local`), C3 (B2, cache `two-level`) xen kẽ: khoảng 8-9 giờ với `REPS=3`, khoảng 6 giờ với `REPS=2`;
   - `b3`: phần đóng góp của B3 (cache token chia sẻ) trên nền B2, cùng một jar bật/tắt bằng `diagramio.cache.share-tokens`: C2, C3 (không cache token) so với C2b3, C3b3; khoảng 2,5 giờ;
   - `tune`: tinh chỉnh runtime của B1 (pool Hikari 20, virtual threads), khoảng 1,5 giờ;
   - `m0`: chỉ C0, khoảng 3,5 giờ.
5. Khi xong, tải `results.tgz` trong tab **Output**, giải nén vào `bench/results/` của repo, rồi commit các file tóm tắt (mục 5).

Nên chạy `quick` trước (khoảng 15 phút: cây làm việc ở ba chế độ cache `none`, `local`, `two-level`, kèm EXPLAIN) để chắc môi trường ổn, rồi mới chạy ma trận thật. Mỗi phiên tự ghi `env.txt` (CPU, cgroup, phiên bản phần mềm) vì máy Kaggle có thể khác nhau giữa các phiên; so sánh hai cấu hình thì chạy chúng trong **cùng một phiên** (run_matrix.sh tự xen kẽ).

## 2. Chạy thử trên máy (Docker)

Kaggle không chạy được Docker, nên các script cài trực tiếp lên máy. Để thử trên máy dev mà không cài gì vào máy, chạy chúng trong một container Ubuntu (giống Kaggle: root, không có systemd):

```bash
docker run -d --name diagramio-bench --shm-size=1g -v "$(pwd):/repo:ro" ubuntu:24.04 sleep infinity
docker exec diagramio-bench git config --global --add safe.directory '*'
docker exec -e PG_SHARED_BUFFERS=1GB -e PG_EFFECTIVE_CACHE=2GB diagramio-bench bash /repo/bench/kaggle/setup.sh
docker exec -e RESULTS_DIR=/tmp/results -e JVM_HEAP=1g -e PG_SHARED_BUFFERS=1GB \
  diagramio-bench bash /repo/bench/kaggle/run_matrix.sh quick
docker cp diagramio-bench:/tmp/results ./bench-results-local    # xem report.md trong thư mục phiên
```

- `quick` build từ cây làm việc hiện tại (`WORKTREE`), kể cả thay đổi chưa commit; trên Kaggle các ma trận thật luôn dùng tag hoặc commit.
- Container không phân giải được tên miền (ví dụ `Could not resolve host`) thì thêm `--dns 1.1.1.1` vào `docker run`.
- Trên Windows (Git Bash) đặt `export MSYS_NO_PATHCONV=1` trước các lệnh `docker exec`, nếu không đường dẫn `/repo/...` bị đổi thành đường dẫn Windows.
- Máy ít RAM thì giảm `PG_SHARED_BUFFERS` và `JVM_HEAP` như trên. Số liệu đo trên máy dev chỉ để thử bộ đo, không đưa vào báo cáo: pha 2 chỉ so sánh trên Kaggle.

## 3. Phép đo

| Kịch bản | Mô tả | Mô hình tải |
|---|---|---|
| W1 | Chuỗi 6 request của pha 1: tạo sơ đồ, sửa, lưu phiên bản, khôi phục, tạo link, mở link | Đóng: 10, 50, 100 VU không nghỉ, như pha 1 |
| W2 | Hỗn hợp 85% đọc / 15% ghi trên dữ liệu 50.000 sơ đồ, sơ đồ "nóng" theo phân bố Zipf | Mở: tốc độ đến cố định 200, 500, 800 req/s; và tăng dần từng bậc để tìm sức chứa |
| W3 | W2 chỉ trên sơ đồ 500 KB - 1,5 MB | Mở: 20, 50 req/s; và tìm sức chứa |

Mức tải theo ma trận: `m0` dùng đúng bảng trên. `b12` dùng W1 50 VU; W2 200, 400 req/s và sức chứa (bậc 100); W3 20 req/s và sức chứa (bắt đầu 10, bậc 20 req/s, để các cấu hình có cache vẫn vừa thời gian của phiên).

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

- Mỗi lần chạy: chép CSDL mẫu, nạp vào bộ đệm, checkpoint, khởi động JVM mới; warm-up không tính (180 giây từ ma trận `b3`; `m0`, `b12` giữ 60 giây như lúc đo), đo 180 giây; lặp 3 lần, báo cáo trung vị và min–max. Báo cáo cảnh báo khi CPU app ở phần ba đầu pha đo cao hơn phần ba cuối quá 25% (JVM chưa ấm hết) và khi có lệnh Redis lỗi.
- Mô hình tải mở: mỗi iteration của k6 là đúng một request, nên tốc độ đến chính là request/giây; thời gian chậm không làm giảm tải gửi tới (không bị coordinated omission).
- Sức chứa: tăng 100 req/s mỗi bậc 30 giây (bỏ 6 giây đầu mỗi bậc); kết quả là bậc cuối cùng còn đạt **p95 ≤ 100 ms, lỗi < 1%, đạt ≥ 95% tốc độ yêu cầu**. Một bậc vượt p95 1 giây hoặc lỗi 5% thì dừng sớm.
- 3 vCPU cho hệ thống được đo (app, PostgreSQL), 1 vCPU cho k6 và bộ thu số liệu (`taskset`). Báo cáo cảnh báo khi k6 dùng quá 80% CPU của nó.
- W2, W3: mỗi VU chỉ ghi vào phân vùng sơ đồ của riêng nó và tự đếm version, nên 409 không phải do tranh chấp; tranh chấp thật để dành cho W4. Có 409 thì hoặc VU nhớ sai version, hoặc server so với một bản cũ trong cache (phiên `b12` ở C3, đã sửa: [kết quả](../docs/pha-2/ket-qua.md)).

Biến môi trường hay dùng (đầy đủ trong `kaggle/env.sh`, `k6/lib/config.js`):

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `REF_<cấu hình>` | theo ma trận (b12: `C0=v2.0-m0`, `C1=v2.1-b1`, `C2=C3=HEAD`) | git ref build cấu hình đó; cùng ref chỉ build một lần |
| `ARGS_<cấu hình>` | theo ma trận (b12: `C2=--diagramio.cache.mode=local`, `C3=--diagramio.cache.mode=two-level`) | tham số thêm cho app của cấu hình đó |
| `REPS`, `WARMUP`, `MEASURE` | theo ma trận (b3: 2, 180, 180) | số lần lặp, giây warm-up, giây đo |
| `SCALE` | 1.0 | quy mô dữ liệu (1.0 = 2.000 user, 50.000 sơ đồ, ~150.000 phiên bản) |
| `SUT_CPUS`, `LOADGEN_CPUS` | 3 CPU đầu, CPU thứ 4 | bố trí CPU |
| `JVM_HEAP` | `2g` | heap cố định (`-Xms` = `-Xmx`) |
| `PG_SHARED_BUFFERS` | `4GB` | bộ đệm PostgreSQL (CSDL mẫu ở scale 1 khoảng 1,1 GB) |
| `REDIS_PORT`, `REDIS_MAXMEMORY`, `REDIS_VERSION` | `6379`, `2gb`, `7.4` | Redis cho cache L2 (chạy trên CPU của hệ thống được đo, không ghi đĩa, xóa sạch trước mỗi lần đo) |

## 4. Số liệu thu được

Mỗi lần chạy có một thư mục `<cấu hình>_<kịch bản>_<mô hình><mức>_r<lần>/`:

| File | Nội dung |
|---|---|
| `meta.json` | commit, tham số của k6 và của app, thời điểm, mã thoát của k6 |
| `k6-summary.json` | độ trễ, throughput, lỗi theo pha đo, theo thao tác, theo bậc tải |
| `procstat.csv` | CPU và bộ nhớ theo nhóm tiến trình (app, PostgreSQL, Redis, k6), steal, độ bận từng CPU, mỗi 5 giây |
| `prom.csv` | số liệu của app (`/actuator/prometheus` trên cổng quản trị 127.0.0.1:9090): thời gian xử lý theo endpoint, pool kết nối, luồng Tomcat, GC, trúng/trượt cache L1 và L2 |
| `pg_stat_*.csv` | câu SQL tốn thời gian nhất, số transaction, khối đọc (kể cả khối TOAST chứa cột `data`), checkpoint |
| `flame.html` | flame graph CPU (chỉ lần chạy có `--profile`) |

Ở mức phiên còn có `explain-<cấu hình>.txt` (kế hoạch đầy đủ) và `explain-<cấu hình>.tsv` (tóm tắt) khi ma trận có EXPLAIN.

`report.py` gộp chúng thành `report.md` (bảng, biểu đồ, kiểm tra tính hợp lệ, tỉ lệ trúng cache, tỉ lệ 304, bảng EXPLAIN) và `summary.csv` (một dòng mỗi lần chạy).

## 5. Lưu kết quả vào repo

Commit thư mục phiên trong `bench/results/` gồm `report.md`, `summary.csv`, `charts/`, `env.txt`, `manifest.json`, `configs.txt` và các file nhỏ của từng lần chạy. Log thô và `prom.csv` (lớn) bị `bench/results/.gitignore` loại ra; mọi con số trong báo cáo đã có trong `summary.csv`.

## 6. Thêm một cấu hình mới

1. Gắn tag cho bản cải tiến (ví dụ `v2.1-b1`). Cải tiến bật/tắt được bằng cờ thì không cần tag riêng: dùng cùng một ref với tham số khác (như C2, C3 của `b12`).
2. Trong `kaggle/run_matrix.sh`, thêm ma trận mới: `CONFIGS`, `DEFAULT_REF`, `DEFAULT_ARGS` và kịch bản cần so sánh. Cấu hình đầu tiên (mốc cũ nhất) tạo schema của CSDL mẫu.
3. Các cấu hình chạy xen kẽ trong cùng phiên; cấu hình nào có migration mới thì Flyway tự chạy khi app khởi động trên bản chép của CSDL mẫu. Ghi đè từng cấu hình khi chạy bằng `REF_<cấu hình>`, `ARGS_<cấu hình>`.
4. Cải tiến nào đổi số câu SQL thì sửa con số trong `SqlPerRequestE2eTest` cùng PR.
