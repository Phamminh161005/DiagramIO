-- Pha 2, B1: index cho các truy vấn danh sách, tìm theo tên và liệt kê link chia sẻ.
-- Các truy vấn tương ứng nằm trong DiagramRepositoryAdapter (luôn sắp theo updated_at DESC, id).

-- "Sơ đồ của tôi": lọc theo chủ, sắp mới sửa trước. Cột đầu là owner_id nên index này thay luôn
-- idx_diagrams_owner (khóa ngoại tới users, dùng khi xóa user kéo theo cascade).
CREATE INDEX idx_diagrams_owner_updated ON diagrams (owner_id, updated_at DESC, id);
DROP INDEX idx_diagrams_owner;

-- Danh sách công khai: index một phần, chỉ chứa sơ đồ công khai (khoảng 10% số dòng).
CREATE INDEX idx_diagrams_public_updated ON diagrams (updated_at DESC, id) WHERE is_public;

-- Tìm theo tên: title ILIKE '%chuỗi%' chỉ dùng được index trigram, B-tree không giúp được.
-- pg_trgm là extension "trusted" (PostgreSQL 13+), chủ CSDL tự tạo được, không cần superuser.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_diagrams_title_trgm ON diagrams USING gin (title gin_trgm_ops);

-- Liệt kê link của một sơ đồ, và cascade khi xóa sơ đồ (trước đây quét cả bảng diagram_shares).
CREATE INDEX idx_diagram_shares_diagram ON diagram_shares (diagram_id);
