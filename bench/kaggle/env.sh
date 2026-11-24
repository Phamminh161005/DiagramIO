# shellcheck shell=bash
# shellcheck disable=SC2034 # các biến ở đây được dùng trong những script source file này
# Biến và hàm dùng chung cho các script trong bench/kaggle. Không chạy trực tiếp: "source env.sh".
# Mọi giá trị đều đổi được bằng biến môi trường trước khi chạy script.

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$BENCH_DIR/.." && pwd)"

# ---- Phiên bản phần mềm cố định (ghi lại trong env.txt của mỗi phiên đo)
JDK_VERSION="${JDK_VERSION:-21.0.13.1+2}"            # Eclipse Temurin
K6_VERSION="${K6_VERSION:-v2.3.0}"
ASYNC_PROFILER_VERSION="${ASYNC_PROFILER_VERSION:-4.6}"
PG_MAJOR="${PG_MAJOR:-16}"

# ---- Thư mục
TOOLS_DIR="${TOOLS_DIR:-/opt/diagramio-bench}"       # JDK, k6, async-profiler
WORK_DIR="${WORK_DIR:-/tmp/diagramio-bench}"         # dữ liệu sinh ra, bản build, file tạm
RESULTS_DIR="${RESULTS_DIR:-$REPO_DIR/bench/results}"
DATASET_DIR="${DATASET_DIR:-$WORK_DIR/dataset}"

JAVA_HOME="${JAVA_HOME_BENCH:-$TOOLS_DIR/jdk}"
JAVA="$JAVA_HOME/bin/java"
K6="$TOOLS_DIR/k6/k6"
ASPROF="$TOOLS_DIR/async-profiler/bin/asprof"
PYTHON="${PYTHON:-python3}"

# ---- PostgreSQL (cụm mặc định của gói Debian/Ubuntu: /etc/postgresql/$PG_MAJOR/main)
PG_PORT="${PG_PORT:-5432}"
PG_SHARED_BUFFERS="${PG_SHARED_BUFFERS:-4GB}"
PG_EFFECTIVE_CACHE="${PG_EFFECTIVE_CACHE:-16GB}"
DB_USER="${DB_USER:-bench}"
DB_PASSWORD="${DB_PASSWORD:-bench}"
DB_TEMPLATE="${DB_TEMPLATE:-bench_template}"
DB_RUN="${DB_RUN:-bench_run}"

# ---- Ứng dụng
APP_PORT="${APP_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-9090}"
JVM_HEAP="${JVM_HEAP:-2g}"
JVM_OPTS="${JVM_OPTS:--Xms$JVM_HEAP -Xmx$JVM_HEAP -XX:+UseG1GC -XX:+AlwaysPreTouch}"
JWT_SECRET="${JWT_SECRET:-diagramio-bench-only-secret-not-for-production-0123456789}"
# Access token sống đủ lâu cho một lần chạy dài (mặc định 15 phút); không ảnh hưởng chi phí kiểm tra token
JWT_MINUTES="${JWT_MINUTES:-480}"
# Tham số chung cho mọi cấu hình, kể cả bản dựng từ tag cũ, để phép so sánh luôn công bằng. Flame graph của M0
# cho thấy Spring Security quan sát (observation) từng filter và mỗi lần cập nhật một LongTaskTimer O(n) theo số
# request đang chạy: gần 8% CPU của app ở W2 là chi phí của chính phần đo. Hai cờ dưới đây tắt phần đó mà vẫn
# giữ số liệu http.server.requests.
BENCH_APP_ARGS="${BENCH_APP_ARGS---management.observations.enable.spring.security=false --management.observations.long-task-timer.enabled=false}"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "[$(date +%H:%M:%S)] LỖI: $*" >&2; exit 1; }

# ---- Bố trí CPU: hệ thống được đo (app, PostgreSQL) và máy tạo tải (k6, bộ thu số liệu) không dùng chung CPU.
# Mặc định lấy 4 CPU đầu tiên mà tiến trình được phép dùng: 3 cho hệ thống được đo, 1 cho tạo tải (giống Kaggle
# 4 vCPU). Máy có nhiều CPU hơn vẫn chỉ dùng 4 CPU này để kết quả so được với Kaggle.
allowed_cpus() {
  # Cpus_allowed_list có dạng "0-3,8-9"; đổi thành "0 1 2 3 8 9" (không cần python, chạy được trước khi cài gì)
  local list part i out=()
  list=$(awk '/^Cpus_allowed_list:/ {print $2}' /proc/self/status)
  IFS=, read -r -a parts <<< "$list"
  for part in "${parts[@]}"; do
    if [[ $part == *-* ]]; then
      for ((i = ${part%-*}; i <= ${part#*-}; i++)); do out+=("$i"); done
    else
      out+=("$part")
    fi
  done
  echo "${out[*]}"
}

cpu_layout() {
  if [ -n "${SUT_CPUS:-}" ] && [ -n "${LOADGEN_CPUS:-}" ]; then
    return
  fi
  local cpus
  read -r -a cpus <<< "$(allowed_cpus)"
  if [ "${#cpus[@]}" -lt 4 ]; then
    die "cần ít nhất 4 CPU để tách hệ thống được đo và máy tạo tải, máy này có ${#cpus[@]}"
  fi
  SUT_CPUS="${cpus[0]},${cpus[1]},${cpus[2]}"
  LOADGEN_CPUS="${cpus[3]}"
}
cpu_layout
SUT_NCPU=$(awk -F, '{print NF}' <<< "$SUT_CPUS")

# psql không in NOTICE (ví dụ "database ... does not exist, skipping") để log gọn
pg_admin() { PGOPTIONS='-c client_min_messages=warning' runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -p "$PG_PORT" "$@"; }
pg_bench() {
  PGOPTIONS='-c client_min_messages=warning' PGPASSWORD="$DB_PASSWORD" \
    psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PORT" -U "$DB_USER" "$@"
}

# Chờ một URL trả 200, tối đa $2 giây
wait_http() {
  local url=$1 timeout=${2:-120} waited=0
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge "$timeout" ]; then
      return 1
    fi
  done
}

# Khởi động app trên CPU của hệ thống được đo. start_app <jar> <database> <thư mục log> [tham số Spring thêm...]
start_app() {
  local jar=$1 database=$2 logdir=$3
  shift 3
  # shellcheck disable=SC2086 # JVM_OPTS cố ý được tách thành nhiều tham số
  taskset -c "$SUT_CPUS" "$JAVA" $JVM_OPTS -XX:ActiveProcessorCount="$SUT_NCPU" \
    -Xlog:gc:file="$logdir/gc.log":uptime,level,tags \
    -jar "$jar" \
    --server.port="$APP_PORT" --management.server.port="$MGMT_PORT" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$PG_PORT/$database" \
    --spring.datasource.username="$DB_USER" --spring.datasource.password="$DB_PASSWORD" \
    --jwt.secret="$JWT_SECRET" --jwt.access-token-minutes="$JWT_MINUTES" \
    $BENCH_APP_ARGS "$@" > "$logdir/app.log" 2>&1 &
  APP_PID=$!
  if ! wait_http "http://127.0.0.1:$APP_PORT/api/v1/health" 180; then
    tail -n 60 "$logdir/app.log" >&2
    kill -KILL "$APP_PID" 2>/dev/null || true
    die "app không khởi động được (log: $logdir/app.log)"
  fi
}

# Dừng app nhẹ nhàng (SIGTERM), quá 30 giây thì SIGKILL
stop_app() {
  [ -n "${APP_PID:-}" ] || return 0
  kill -TERM "$APP_PID" 2>/dev/null || true
  local waited=0
  while kill -0 "$APP_PID" 2>/dev/null; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge 30 ]; then
      kill -KILL "$APP_PID" 2>/dev/null || true
      break
    fi
  done
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=
}

# CSDL của một lần chạy: chép nguyên từ bench_template rồi nạp vào bộ đệm, để lần chạy nào cũng bắt đầu
# từ cùng một trạng thái (dữ liệu ghi của lần trước không ảnh hưởng lần sau)
reset_run_db() {
  pg_admin -d postgres -c "DROP DATABASE IF EXISTS $DB_RUN WITH (FORCE)"
  pg_admin -d postgres -c "CREATE DATABASE $DB_RUN TEMPLATE $DB_TEMPLATE OWNER $DB_USER STRATEGY FILE_COPY"
  pg_admin -d "$DB_RUN" -c "SELECT count(pg_prewarm(c.oid)) FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname IN ('public', 'pg_toast') AND c.relkind IN ('r', 'i', 't')" > /dev/null
  pg_admin -d postgres -c "CHECKPOINT"
}
