#!/usr/bin/env bash
# Kế hoạch thực thi (EXPLAIN ANALYZE, BUFFERS) của các truy vấn danh sách, tìm kiếm, link chia sẻ và lịch sử trên
# dữ liệu chuẩn, cho một cấu hình: so sánh trước (câu do Hibernate sinh ở C0) và sau (SQL viết tay ở B1).
#
#   bash bench/kaggle/explain.sh <config> <app.jar> <thư mục ra>
#
# Chép CSDL mẫu, khởi động app một lần để Flyway áp migration của cấu hình đó (B1 thêm index ở V4), dừng app,
# rồi chạy từng câu 3 lần và giữ lần cuối (cache đã ấm, như lúc đang chịu tải). Tham số lấy từ chính dữ liệu:
# user có nhiều sơ đồ nhất, sơ đồ có nhiều phiên bản nhất, sơ đồ có nhiều link nhất.
# Kết quả: <thư mục ra>/explain-<config>.txt (kế hoạch đầy đủ) và explain-<config>.tsv (tóm tắt).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

config=${1:?"cách dùng: explain.sh <config> <app.jar> <thư mục ra>"}
jar=${2:?}
out_dir=${3:?}
mkdir -p "$out_dir"

reset_run_db
logdir=$(mktemp -d)
start_app "$jar" "$DB_RUN" "$logdir"
stop_app
rm -rf "$logdir"

q() { pg_bench -d "$DB_RUN" -At -c "$1"; }
owner=$(q "SELECT owner_id FROM diagrams GROUP BY owner_id ORDER BY count(*) DESC, owner_id LIMIT 1")
owned=$(q "SELECT count(*) FROM diagrams WHERE owner_id = '$owner'")
word=$(q "SELECT split_part(title, ' ', 1) FROM diagrams WHERE owner_id = '$owner' ORDER BY id LIMIT 1")
history=$(q "SELECT diagram_id FROM diagram_versions GROUP BY diagram_id ORDER BY count(*) DESC, diagram_id LIMIT 1")
shared=$(q "SELECT diagram_id FROM diagram_shares GROUP BY diagram_id ORDER BY count(*) DESC, diagram_id LIMIT 1")
has_v4=$(q "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_diagrams_owner_updated'")

all_cols="id, created_at, data, diagram_type, is_public, owner_id, title, updated_at, updated_by, version"
summary_cols="id, title, diagram_type, version, is_public, owner_id, updated_by, created_at, updated_at"
declare -a NAMES SQLS
add() { NAMES+=("$1"); SQLS+=("$2"); }
if [ "$config" = C0 ]; then
  # Đúng dạng câu Hibernate sinh ra ở C0 (xem target/bench/sql-per-request.md của tag v2.0-m0)
  add public_count "SELECT count(id) FROM diagrams WHERE is_public"
  add public_page10 "SELECT $all_cols FROM diagrams WHERE is_public OFFSET 200 ROWS FETCH FIRST 20 ROWS ONLY"
  add mine_page "SELECT $all_cols FROM diagrams WHERE owner_id = '$owner' OFFSET 0 ROWS FETCH FIRST 20 ROWS ONLY"
  add mine_count "SELECT count(id) FROM diagrams WHERE owner_id = '$owner'"
  add search_page "SELECT $all_cols FROM diagrams WHERE owner_id = '$owner' AND upper(title) LIKE upper('%$word%') ESCAPE '\\' OFFSET 0 ROWS FETCH FIRST 20 ROWS ONLY"
  add search_count "SELECT count(id) FROM diagrams WHERE owner_id = '$owner' AND upper(title) LIKE upper('%$word%') ESCAPE '\\'"
  add shares "SELECT id, created_at, diagram_id, role, share_token FROM diagram_shares WHERE diagram_id = '$shared'"
  add history "SELECT id, created_at, created_by, data, diagram_id, note, version_number FROM diagram_versions WHERE diagram_id = '$history' ORDER BY version_number DESC"
else
  # Đúng dạng câu trong DiagramRepositoryAdapter, DiagramVersionRepositoryAdapter (B1)
  page() {  # page <điều kiện> <offset>
    echo "SELECT t.total, p.* FROM (SELECT count(*) AS total FROM diagrams WHERE $1) t LEFT JOIN LATERAL (SELECT $summary_cols FROM diagrams WHERE $1 ORDER BY updated_at DESC, id LIMIT 20 OFFSET $2) p ON true"
  }
  add public_page10 "$(page "is_public" 200)"
  add mine_page "$(page "owner_id = '$owner'" 0)"
  add search_page "$(page "owner_id = '$owner' AND title ILIKE '%$word%' ESCAPE '\\'" 0)"
  add shares "SELECT id, created_at, diagram_id, role, share_token FROM diagram_shares WHERE diagram_id = '$shared'"
  add history "SELECT id, version_number, note, created_by, created_at FROM diagram_versions WHERE diagram_id = '$history' ORDER BY version_number DESC"
fi

txt="$out_dir/explain-$config.txt"
tsv="$out_dir/explain-$config.tsv"
{
  echo "# $config, jar $(cat "$(dirname "$jar")/git-sha" 2>/dev/null || echo '?'), index V4: $has_v4"
  echo "# owner $owner ($owned sơ đồ), từ khóa '$word', lịch sử của $history, link của $shared"
} > "$txt"
printf 'query\texecution_ms\tshared_hit\tshared_read\trows\ttop_node\n' > "$tsv"
for i in "${!NAMES[@]}"; do
  name=${NAMES[$i]} sql=${SQLS[$i]}
  for _ in 1 2; do q "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql" > /dev/null; done
  plan_json=$(q "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql")
  { echo; echo "## $name"; echo "$sql"; q "EXPLAIN (ANALYZE, BUFFERS) $sql"; } >> "$txt"
  PLAN_JSON=$plan_json "$PYTHON" - "$name" >> "$tsv" << 'EOF'
import json, os, sys
plan = json.loads(os.environ["PLAN_JSON"])[0]
root = plan["Plan"]
def first_scan(node):
    if "Scan" in node["Node Type"]:
        return node["Node Type"] + (" " + node["Index Name"] if "Index Name" in node else "")
    for child in node.get("Plans", []):
        found = first_scan(child)
        if found:
            return found
    return ""
print("\t".join([sys.argv[1], f'{plan["Execution Time"]:.3f}', str(root.get("Shared Hit Blocks", 0)),
                 str(root.get("Shared Read Blocks", 0)), str(root.get("Actual Rows", 0)),
                 f'{root["Node Type"]} / {first_scan(root)}']))
EOF
done
log "EXPLAIN $config: $tsv"
column -t -s $'\t' "$tsv" >&2 || cat "$tsv" >&2
