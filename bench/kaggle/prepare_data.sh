#!/usr/bin/env bash
# Tạo CSDL mẫu $DB_TEMPLATE một lần mỗi phiên: schema do Flyway của chính jar được đo tạo ra, rồi nạp dữ liệu
# sinh xác định (bench/seed/gen.py). Mỗi lần chạy đo sau đó chép CSDL này thành $DB_RUN (reset_run_db).
#
#   bash bench/kaggle/prepare_data.sh <app.jar> [scale]
#
# Dữ liệu đã sinh (cùng scale, seed, phiên bản bộ sinh) được dùng lại, không sinh lại.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

jar=${1:?"cách dùng: prepare_data.sh <app.jar> [scale]"}
scale=${2:-${SCALE:-1.0}}
seed=${SEED:-20261123}
[ -f "$jar" ] || die "không thấy $jar"

# ---- 1. Sinh dữ liệu (hoặc dùng lại)
manifest="$DATASET_DIR/manifest.json"
reuse=$("$PYTHON" - "$manifest" "$scale" "$seed" "$BENCH_DIR/seed/gen.py" << 'EOF'
import json, re, sys
path, scale, seed, gen = sys.argv[1:]
try:
    m = json.load(open(path))
    version = int(re.search(r"^GENERATOR_VERSION = (\d+)", open(gen).read(), re.M).group(1))
    print("yes" if m["scale"] == float(scale) and m["seed"] == int(seed) and m["generator_version"] == version else "no")
except Exception:
    print("no")
EOF
)
if [ "$reuse" = yes ] && [ -f "$DATASET_DIR/csv/diagrams.tsv" ]; then
  log "Dùng lại dữ liệu đã sinh ở $DATASET_DIR (scale $scale, seed $seed)"
else
  log "Sinh dữ liệu scale $scale, seed $seed"
  rm -rf "$DATASET_DIR"
  "$PYTHON" -I "$BENCH_DIR/seed/gen.py" --out "$DATASET_DIR" --scale "$scale" --seed "$seed"
fi

# ---- 2. CSDL trống, schema do Flyway của app tạo (giống hệt khi app chạy thật)
log "Tạo $DB_TEMPLATE và chạy Flyway bằng $jar"
pg_admin -d postgres -c "ALTER DATABASE $DB_TEMPLATE IS_TEMPLATE false ALLOW_CONNECTIONS true" 2>/dev/null || true
pg_admin -d postgres -c "DROP DATABASE IF EXISTS $DB_TEMPLATE WITH (FORCE)"
pg_admin -d postgres -c "CREATE DATABASE $DB_TEMPLATE OWNER $DB_USER"
logdir=$(mktemp -d)
start_app "$jar" "$DB_TEMPLATE" "$logdir"
stop_app
grep -q "Successfully applied\|Schema .* is up to date" "$logdir/app.log" || { tail -n 40 "$logdir/app.log"; die "Flyway không chạy?"; }
rm -rf "$logdir"

# ---- 3. Nạp dữ liệu bằng COPY (định dạng text, đúng thứ tự cột gen.py ghi ra)
log "Nạp dữ liệu vào $DB_TEMPLATE"
started=$(date +%s)
pg_bench -d "$DB_TEMPLATE" << EOF
\\copy users (id, username, email, password_hash, created_at, updated_at) FROM '$DATASET_DIR/csv/users.tsv'
\\copy diagrams (id, title, diagram_type, data, version, is_public, owner_id, updated_by, created_at, updated_at) FROM '$DATASET_DIR/csv/diagrams.tsv'
\\copy diagram_versions (id, diagram_id, version_number, data, note, created_by, created_at) FROM '$DATASET_DIR/csv/diagram_versions.tsv'
\\copy diagram_shares (id, diagram_id, share_token, role, created_at) FROM '$DATASET_DIR/csv/diagram_shares.tsv'
EOF
pg_admin -d "$DB_TEMPLATE" -c "CREATE EXTENSION IF NOT EXISTS pg_prewarm"
pg_admin -d "$DB_TEMPLATE" -c "VACUUM (ANALYZE, FREEZE)"
log "Nạp xong trong $(( $(date +%s) - started ))s"

# ---- 4. Kiểm tra số dòng khớp manifest, ghi kích thước bảng
"$PYTHON" - "$manifest" << EOF
import json, subprocess, sys
counts = json.load(open(sys.argv[1]))["counts"]
expected = {"users": counts["users"] + 1, "diagrams": counts["diagrams"],   # +1: tài khoản mẫu của V2
            "diagram_versions": counts["diagram_versions"], "diagram_shares": counts["diagram_shares"]}
sql = " UNION ALL ".join(f"SELECT '{t}', count(*) FROM {t}" for t in expected)
out = subprocess.run(["runuser", "-u", "postgres", "--", "psql", "-X", "-At", "-F", " ", "-p", "$PG_PORT",
                      "-d", "$DB_TEMPLATE", "-c", sql], check=True, capture_output=True, text=True).stdout
actual = {name: int(n) for name, n in (line.split() for line in out.strip().splitlines())}
if actual != expected:
    sys.exit(f"số dòng không khớp manifest: {actual} != {expected}")
print("số dòng khớp manifest:", actual)
EOF
pg_admin -d "$DB_TEMPLATE" -At -F ' ' -c "SELECT relname, pg_size_pretty(pg_total_relation_size(c.oid)),
    pg_total_relation_size(c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY 3 DESC" | tee "$DATASET_DIR/db-sizes.txt"

# ---- 5. Khóa thành template: không ai kết nối vào được, chỉ dùng để chép
pg_admin -d postgres -c "ALTER DATABASE $DB_TEMPLATE IS_TEMPLATE true ALLOW_CONNECTIONS false"
pg_admin -d postgres -c "CHECKPOINT"
# File TSV (3,3 GB ở scale 1) không còn cần sau khi nạp; fixture và manifest được giữ cho k6 và báo cáo
if [ "${KEEP_DATASET_CSV:-0}" != 1 ]; then
  rm -rf "$DATASET_DIR/csv"
fi
log "CSDL mẫu $DB_TEMPLATE sẵn sàng ($(pg_admin -d postgres -At -c "SELECT pg_size_pretty(pg_database_size('$DB_TEMPLATE'))"))"
