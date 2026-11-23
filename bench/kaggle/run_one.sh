#!/usr/bin/env bash
# Một lần đo: chép CSDL mẫu, khởi động app, thu số liệu phía server, chạy k6, xuất thống kê PostgreSQL.
#
#   bash bench/kaggle/run_one.sh --config C0 --jar <app.jar> --workload w2 --mode fixed --level 500 \
#        --rep 1 --session <thư mục phiên> [--profile]
#
#   --workload  w1 | w2 | w3 (W3 = kịch bản W2 chỉ trên sơ đồ lớn)
#   --mode      closed (level = số VU) | fixed (level = request/giây) | capacity (level bỏ qua; bậc tải lấy từ
#               RATE_START, RATE_STEP, RATE_MAX, STEP)
#   --profile   lấy flame graph CPU (async-profiler) PROFILE_SECONDS giây ở giữa pha đo; lần chạy này
#               bị nhiễu bởi profiler nên báo cáo không gộp nó vào số liệu độ trễ
#
# Kết quả ở <phiên>/<config>_<workload>_<mode><level>_r<rep>/: meta.json, k6-summary.json, procstat.csv,
# prom.csv, pg_*.csv; log thô (app.log, gc.log, k6.log) không commit.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

config='' jar='' workload='' mode='' level='' rep=1 session='' profile=0
while [ $# -gt 0 ]; do
  case "$1" in
    --config) config=$2; shift 2 ;;
    --jar) jar=$2; shift 2 ;;
    --workload) workload=$2; shift 2 ;;
    --mode) mode=$2; shift 2 ;;
    --level) level=$2; shift 2 ;;
    --rep) rep=$2; shift 2 ;;
    --session) session=$2; shift 2 ;;
    --profile) profile=1; shift ;;
    *) die "tham số lạ: $1" ;;
  esac
done
[ -n "$config" ] && [ -f "$jar" ] && [ -n "$workload" ] && [ -n "$mode" ] && [ -n "$session" ] \
  || die "thiếu tham số, xem đầu file run_one.sh"

WARMUP=${WARMUP:-60}
MEASURE=${MEASURE:-180}
PROFILE_SECONDS=${PROFILE_SECONDS:-60}

k6_env=(-e "MODE=$mode" -e "WARMUP=$WARMUP" -e "MEASURE=$MEASURE" -e "WORKLOAD=$workload"
        -e "FIXTURES=$DATASET_DIR/fixtures" -e "BASE_URL=http://127.0.0.1:$APP_PORT")
case "$workload" in
  w1) script=w1.js ;;
  w2) script=w2.js ;;
  w3) script=w2.js; k6_env+=(-e SIZE_CLASS=L) ;;
  *) die "workload chưa hỗ trợ: $workload" ;;
esac
case "$mode" in
  closed) [ -n "$level" ] || die "closed cần --level (số VU)"; k6_env+=(-e "VUS=$level") ;;
  fixed) [ -n "$level" ] || die "fixed cần --level (request/giây)"; k6_env+=(-e "RATE=$level") ;;
  capacity) level=auto ;;
  *) die "mode không hợp lệ: $mode" ;;
esac
for name in RATE_START RATE_STEP RATE_MAX STEP SETTLE MAX_VUS PRE_VUS ABORT_P95_MS ABORT_ERROR_RATE ZIPF_S MIX \
            PAGE_SIZE W1_PAYLOAD PASSWORD; do
  if [ -n "${!name:-}" ]; then
    k6_env+=(-e "$name=${!name}")
  fi
done

run="${config}_${workload}_${mode}${level}_r${rep}"
run_dir="$session/$run"
rm -rf "$run_dir" && mkdir -p "$run_dir"
log "=== $run"

cleanup() {
  for pid in ${PROC_PID:-} ${PROM_PID:-} ${PROFILER_PID:-}; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  stop_app
  # Lần chạy hỏng giữa chừng vẫn phải hiện trong báo cáo (report.py chỉ đọc thư mục có meta.json)
  if [ ! -f "$run_dir/meta.json" ]; then
    printf '{"run": "%s", "config": "%s", "workload": "%s", "mode": "%s", "level": "%s", "rep": "%s", "profiled": %s, "status": "failed", "k6_exit": -1, "warmup_s": %s, "measure_s": %s, "sut_cpus": "%s"}\n' \
      "$run" "$config" "$workload" "$mode" "$level" "$rep" "$([ "$profile" = 1 ] && echo true || echo false)" \
      "$WARMUP" "$MEASURE" "$SUT_CPUS" > "$run_dir/meta.json"
  fi
}
trap cleanup EXIT

reset_run_db
if [ "$profile" = 1 ]; then
  # Thông tin gỡ lỗi giữa các safepoint, để flame graph không bị lệch về điểm safepoint
  JVM_OPTS="$JVM_OPTS -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
fi
start_app "$jar" "$DB_RUN" "$run_dir"

taskset -c "$LOADGEN_CPUS" "$PYTHON" "$BENCH_DIR/collect/procstat.py" --out "$run_dir/procstat.csv" &
PROC_PID=$!
taskset -c "$LOADGEN_CPUS" "$PYTHON" "$BENCH_DIR/collect/prom_scrape.py" \
  --url "http://127.0.0.1:$MGMT_PORT/actuator/prometheus" --out "$run_dir/prom.csv" 2> "$run_dir/prom_scrape.log" &
PROM_PID=$!
bash "$BENCH_DIR/collect/pg_stats.sh" reset

profile_mid_measure() {
  local start_ms='' begin wait_s event
  for _ in $(seq 1 1200); do
    start_ms=$(grep -oE 'SCENARIO_START [0-9]+' "$run_dir/k6.log" 2>/dev/null | head -n 1 | cut -d' ' -f2 || true)
    [ -n "$start_ms" ] && break
    sleep 0.5
  done
  if [ -z "$start_ms" ]; then
    echo "không thấy SCENARIO_START trong k6.log" > "$run_dir/asprof.log"
    return
  fi
  begin=$(( start_ms / 1000 + WARMUP + MEASURE / 2 - PROFILE_SECONDS / 2 ))
  wait_s=$(( begin - $(date +%s) ))
  [ "$wait_s" -gt 0 ] && sleep "$wait_s"
  # perf_events thường bị chặn trong container; khi đó dùng bộ đếm thời gian CPU (ctimer, itimer)
  for event in cpu ctimer itimer; do
    if "$ASPROF" -d "$PROFILE_SECONDS" -e "$event" -f "$run_dir/flame.html" "$APP_PID" >> "$run_dir/asprof.log" 2>&1 \
        && [ -s "$run_dir/flame.html" ]; then
      echo "$event" > "$run_dir/flame-event.txt"
      return
    fi
  done
}
if [ "$profile" = 1 ]; then
  profile_mid_measure &
  PROFILER_PID=$!
fi

started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
set +e
taskset -c "$LOADGEN_CPUS" "$K6" run --quiet --no-color "${k6_env[@]}" -e "OUT=$run_dir/k6-summary.json" \
  "$BENCH_DIR/k6/$script" > "$run_dir/k6.log" 2>&1
k6_exit=$?
set -e
ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
if [ -n "${PROFILER_PID:-}" ]; then
  wait "$PROFILER_PID" 2>/dev/null || true
  PROFILER_PID=
fi

kill -TERM "$PROC_PID" "$PROM_PID" 2>/dev/null || true
wait "$PROC_PID" "$PROM_PID" 2>/dev/null || true
PROC_PID='' PROM_PID=''
# Dừng app trước: backend PostgreSQL đẩy hết thống kê khi kết nối đóng
stop_app
sleep 1
bash "$BENCH_DIR/collect/pg_stats.sh" dump "$run_dir"

# 0: đạt; 99: có ngưỡng bị vượt (với capacity là đã dừng sớm khi quá tải, bình thường)
status=ok
if [ "$k6_exit" != 0 ] && { [ "$k6_exit" != 99 ] || [ "$mode" != capacity ]; }; then
  status=failed
fi
META_RUN="$run" META_CONFIG="$config" META_WORKLOAD="$workload" META_MODE="$mode" META_LEVEL="$level" \
META_REP="$rep" META_PROFILE="$profile" META_STARTED="$started_at" META_ENDED="$ended_at" META_K6_EXIT="$k6_exit" \
META_STATUS="$status" META_SHA="$(cat "$(dirname "$jar")/git-sha" 2>/dev/null || echo unknown)" \
META_JVM="$JVM_OPTS" META_SUT="$SUT_CPUS" META_LOADGEN="$LOADGEN_CPUS" META_WARMUP="$WARMUP" META_MEASURE="$MEASURE" \
META_K6_ENV="${k6_env[*]}" META_MANIFEST="$DATASET_DIR/manifest.json" \
"$PYTHON" - "$run_dir/meta.json" << 'EOF'
import hashlib, json, os, sys
e = os.environ
manifest = e["META_MANIFEST"]
meta = {
    "run": e["META_RUN"], "config": e["META_CONFIG"], "git_sha": e["META_SHA"], "workload": e["META_WORKLOAD"],
    "mode": e["META_MODE"], "level": e["META_LEVEL"], "rep": e["META_REP"], "profiled": e["META_PROFILE"] == "1",
    "status": e["META_STATUS"], "k6_exit": int(e["META_K6_EXIT"]), "started_at": e["META_STARTED"],
    "ended_at": e["META_ENDED"], "warmup_s": int(e["META_WARMUP"]), "measure_s": int(e["META_MEASURE"]),
    "jvm_opts": e["META_JVM"], "sut_cpus": e["META_SUT"], "loadgen_cpus": e["META_LOADGEN"],
    "k6_args": e["META_K6_ENV"],
    "dataset_manifest_sha256": hashlib.sha256(open(manifest, "rb").read()).hexdigest() if os.path.exists(manifest) else None,
}
with open(sys.argv[1], "w") as f:
    json.dump(meta, f, indent=1)
EOF
tail -n 25 "$run_dir/k6.log"
if [ "$status" != ok ]; then
  log "CẢNH BÁO: $run kết thúc với mã k6 $k6_exit (xem $run_dir/k6.log)"
fi
