# ADR-0005: Rà soát kiến trúc và gia cố sau B3

- Trạng thái: Đã triển khai
- Mốc: giữa B3 và B4 trong [kế hoạch pha 2](../ke-hoach-pha-2.md), trong lúc chờ số đo Kaggle của `b12`
- Liên quan: [ADR-0002](0002-toi-uu-truy-cap-du-lieu.md) (B1), [ADR-0003](0003-cache-hai-tang-etag.md) (B2), [ADR-0004](0004-cache-token-chia-se.md) (B3)

## Bối cảnh

Trước khi chuyển sang chạy nhiều instance (B4), toàn bộ hệ thống sau M0–B3 được rà soát theo hai góc nhìn: kiến trúc phần mềm (đúng đắn, an toàn, nhất quán giữa mã và tài liệu) và vận hành (build, image, triển khai, kiểm tra tự động). Nguyên tắc của đợt rà soát: **mỗi nghi vấn phải được kiểm chứng bằng thực nghiệm trước khi sửa**, và mỗi bản sửa có test làm đỏ khi bỏ bản sửa đi.

## Phát hiện và quyết định

| # | Phát hiện | Bằng chứng | Quyết định |
|---|---|---|---|
| 1 | Chế độ `two-level`: Redis chưa lên lúc app khởi động thì **app dừng hẳn** | Chạy jar với Redis tắt: `Failed to start bean 'redisCacheMonitor'`, "Application run failed" (Tomcat kịp nhận request vài giây rồi tắt) | Việc đăng ký kênh pub/sub chuyển vào luồng của bộ theo dõi, thất bại thì thử lại ở nhịp sau; trong lúc đó cache đọc thẳng CSDL. Đúng với nguyên tắc "Redis lỗi thì chạy tiếp bằng CSDL" và cần cho B4 (Redis khởi động chậm hơn app, kịch bản W7) |
| 2 | **Body request không giới hạn**: giới hạn 2 MB của `data` chỉ được kiểm tra sau khi Jackson đã parse cả body thành cây JSON | PUT 66 MB: đọc và parse hết (1,15 giây) rồi mới trả 400. 6 request như vậy cùng lúc, heap 512 MB: `OutOfMemoryError`, 5 request lỗi 500. Ai tự đăng ký tài khoản hoặc cầm link edit cũng làm được; `POST /auth/login` (công khai) cũng đọc hết body | `RequestSizeLimitFilter` đứng trước mọi filter khác (kể cả Spring Security): `Content-Length` vượt giới hạn thì trả 413 ngay, không đọc; body chunked được đếm khi đọc và dừng ở byte đầu tiên vượt giới hạn (cũng 413). Giới hạn vận chuyển mặc định 4 MB (`diagramio.http.max-request-size`), quy tắc 2 MB của `data` vẫn trả 400 như cũ. Sau khi sửa, cùng cuộc tấn công: 6 lần 413 trong 5-10 ms, không còn lỗi bộ nhớ |
| 3 | **Cache có thể vượt bộ nhớ**: (a) trọng số tính bằng số ký tự, nhưng chuỗi có ký tự ngoài Latin-1 (tiếng Việt: "ư", "đ"...) chiếm 2 byte mỗi ký tự; (b) dung lượng cố định 256 MB + 128 MB, trong khi heap mặc định của JVM trong container chỉ là 25% RAM | (a) đặc tả compact strings của Java; (b) container 1 GB, cờ mặc định: heap 256 MB, nhỏ hơn tổng dung lượng cache | Trọng số đếm đúng byte trong heap (`CachedDiagram.heapBytes`). Dung lượng thật của mỗi cache bằng giá trị cấu hình nhưng không quá 25% heap (sơ đồ) và 10% heap (phiên bản), ghi log lúc khởi động. Với heap 2 GB của bộ đo, giá trị không đổi |
| 4 | Mỗi lần ghi sơ đồ ở `two-level` tốn **hai lượt tới Redis** (ghi có so version, rồi publish) | Đọc mã `DiagramCache.written` | Publish nằm luôn trong script Lua ghi có so version: một lượt. Publish cả khi Redis đang có bản mới hơn (CSDL đã đổi, instance khác vẫn cần bỏ bản cũ hơn) |
| 5 | **Vận hành**: (a) `mvnw` và các script `.sh` không có quyền thực thi trong git (`100644`), nên `./mvnw test` trong README lỗi "Permission denied" trên Linux/Mac và CI; (b) build context Docker ~600 MB (kết quả đo, dữ liệu cục bộ) dù image chỉ cần `pom.xml` và `src/`; (c) image một khối: mỗi lần sửa mã đổi cả ~80 MB; JVM không biết là đang trong container; (d) không có CI | `git ls-files -s`, `du`, `docker history` | (a) đặt `100755`; (b) `.dockerignore` loại `bench/`, `results/`: context còn 18 KB; (c) Dockerfile tách jar theo lớp (`-Djarmode=tools extract --layers`): lớp mã của app 991 KB, thư viện 78 MB ở lớp riêng; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (container 1 GB: heap 742 MB, cache tự co về 185 MB; hết bộ nhớ thì thoát để Docker khởi động lại); (d) GitHub Actions: `./mvnw verify` (toàn bộ test, kể cả Testcontainers và hai instance), `shellcheck` các script bộ đo, kiểm tra cú pháp Python, `docker compose config`, build image. Workflow được kiểm bằng `actionlint` |
| 6 | Tài liệu lệch mã: `luong-nghiep-vu.md` còn mô tả JPA `@Version`, service gọi `caller.canX` | Đọc tài liệu | Cập nhật theo B1–B3 và bản gia cố này |

## Các phương án đã cân nhắc

| Vấn đề | Phương án | Chọn và lý do |
|---|---|---|
| Giới hạn body | `StreamReadConstraints.maxDocumentLength` của Jackson; giới hạn của Tomcat; `client_max_body_size` của Nginx; filter trong app | **Filter trong app**: Jackson chỉ dừng khi đã đọc tới giới hạn và trả 400 (không phân biệt được với JSON sai); Tomcat chỉ giới hạn form; Nginx (B4) sẽ chặn thêm ở biên nhưng app vẫn phải tự bảo vệ khi được gọi trực tiếp (dev, Kaggle, Docker không qua proxy) |
| Khởi động khi Redis chết | Đánh dấu bean theo dõi là lazy; bọc `start()` trong try/catch một lần; đăng ký lại trong vòng theo dõi | **Vòng theo dõi**: không chỉ sống sót lúc khởi động mà còn tự đăng ký khi Redis lên muộn, cùng một cơ chế với lúc Redis chết giữa chừng |
| Kích thước cache | Bắt người vận hành tự chỉnh theo heap; tỉ lệ cố định của heap; min(cấu hình, tỉ lệ heap) | **min(cấu hình, tỉ lệ heap)**: cấu hình vẫn có hiệu lực khi heap đủ (kết quả đo không đổi), và không bao giờ vượt heap khi container nhỏ |
| Khởi động nhanh hơn (CDS, AOT) | Thêm vào Dockerfile | **Chưa làm**: lượt chạy huấn luyện CDS cần app khởi động tới hết (Flyway cần CSDL) trong lúc build image. Xem lại ở B4 nếu thời gian khởi động ảnh hưởng tới W7 |

## Cố ý không đổi

- **Ghi L2 bất đồng bộ, hoặc chỉ ghi "mốc version" lên Redis cho sơ đồ lớn**: có thể giảm chi phí ghi của `two-level` (chạy thử `quick` trên máy dev cho thấy W1, kịch bản nặng về ghi, chậm hơn `local`). Chưa đổi vì chưa có số đo Kaggle (C3 trong `b12`); nếu số đo cho thấy chi phí ghi đáng kể thì làm sau, có số trước và sau.
- **Index V4 tạo không `CONCURRENTLY`**: khóa ghi trên bảng lớn trong lúc tạo. V4 đã merge vào `main`, sửa thì Flyway báo sai checksum (AGENTS.md cấm). Migration sau này trên bảng lớn nên dùng `CREATE INDEX CONCURRENTLY` (Flyway: script không chạy trong transaction).
- **Kích thước pool Hikari, virtual threads**: quyết định bằng ma trận `tune`, không đổi theo cảm tính.
- **Nén gzip response**: trên localhost của bộ đo chỉ tốn CPU (sơ đồ 1,5 MB nén mất vài chục ms CPU) mà không lợi gì; làm ở Nginx (B4) cho client thật qua mạng.

## Kiểm chứng

- Test mới: `RequestLimitsE2eTest` (413 khi có `Content-Length`, 413 khi gửi chunked, endpoint công khai, data quá 2 MB vẫn 400), `RedisDownAtStartupE2eTest` (app khởi động và phục vụ bằng CSDL khi không có Redis; thu hồi link trả 503), `RedisDiagramCacheTest` (instance khởi động lúc Redis đang treo thì đọc CSDL, Redis trở lại thì tự đăng ký và nhận thông báo từ instance khác), `CachedDiagramTest`, `CacheConfigTest`, `GlobalExceptionHandlerTest` (503).
- Thử bỏ bản sửa #1: `RedisDownAtStartupE2eTest` đỏ với đúng lỗi `Failed to start bean 'redisCacheMonitor'`.
- Chạy lại cuộc tấn công của #2 trên bản đã sửa (heap 512 MB): 6 lần 413, 5-10 ms, không có `OutOfMemoryError`.
- Image: build được, lớp mã 991 KB; chạy trong container 1 GB với user không phải root, heap 742 MB, cache 185 MB, `/api/v1/health` trả 200.
- Toàn bộ `./mvnw test` xanh.
