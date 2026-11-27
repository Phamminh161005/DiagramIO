#!/usr/bin/env bash
# Chạy một ma trận đo trọn vẹn trong một phiên: ghi cấu hình máy, build jar từng cấu hình, tạo CSDL mẫu,
# chạy mọi phép đo (lặp REPS lần, xen kẽ các cấu hình trong mỗi vòng), cuối cùng tổng hợp báo cáo.
#
#   bash bench/kaggle/run_matrix.sh [b12|b3|tune|m0|quick]
#
# b12    C0 (v2.0-m0), C1 (B1, tag v2.1-b1), C2 (B2, cache Caffeine), C3 (B2, Caffeine + Redis): W1, W2, W3 và
#        EXPLAIN của C0, C1; mỗi phép đo 3 lần, khoảng 8-9 giờ trên Kaggle (REPS=2: khoảng 6 giờ)
# b3     phần đóng góp của B3 (cache token chia sẻ) trên nền B2, cùng một jar bật/tắt bằng cờ: C2, C3 không có cache
#        token; C2b3, C3b3 có; W2 400 và 700 req/s (dưới mức k6 thành nút thắt, khoảng 850 req/s trên 1 vCPU) và
#        sức chứa, mỗi phép đo 2 lần, khoảng 4-5 giờ. Cũng là lần đo lại C2, C3 sau bản sửa cache (phiên b12)
# tune   tinh chỉnh runtime của B1, mỗi lần một biến: pool Hikari 10 (mặc định) / 20, virtual threads; W2
#
# Warm-up 180 giây từ b3 trở đi: phiên b12 (60 giây) cho thấy JIT còn chạy suốt phút đầu của pha đo (CPU app ở
# phần ba đầu cao hơn phần ba cuối 30-90%); b12 và m0 giữ 60 giây để chạy lại so được với phiên đã có.
# m0     cấu hình C0 (hiện trạng), kịch bản W1-W3, mỗi phép đo 3 lần: khoảng 3-4 giờ trên Kaggle
# quick  chạy thử vài phút với dữ liệu nhỏ, để kiểm tra môi trường (gồm Redis, EXPLAIN) trước khi chạy thật
#
# Mỗi cấu hình là một git ref (REF_<cấu hình>) cộng tham số cho app (ARGS_<cấu hình>); hai biến này ghi đè giá trị
# mặc định của ma trận. Cùng một ref chỉ build một lần (C2, C3 dùng chung một file jar, khác chế độ cache).
# Biến môi trường khác: REPS, SCALE, WARMUP, MEASURE, RESULTS_DIR.
# Kết quả: $RESULTS_DIR/<thời điểm>-<ma trận>/ gồm env.txt, configs.txt, manifest.json, mỗi lần chạy một thư mục,
# explain-*.txt|tsv, report.md.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

matrix=${1:-b12}
declare -A DEFAULT_REF=() DEFAULT_ARGS=()
EXPLAIN=()
case "$matrix" in
  b12)
    CONFIGS=(C0 C1 C2 C3)
    DEFAULT_REF=([C0]=v2.0-m0 [C1]=v2.1-b1 [C2]=HEAD [C3]=HEAD)
    DEFAULT_ARGS=([C2]="--diagramio.cache.mode=local" [C3]="--diagramio.cache.mode=two-level")
    EXPLAIN=(C0 C1)
    : "${REPS:=3}" "${SCALE:=1.0}" "${WARMUP:=60}" "${MEASURE:=180}"
    # workload mode level [biến môi trường riêng cho lần chạy]. Bậc W3 là 20 req/s để vừa thời gian khi cache
    # nâng sức chứa lên nhiều lần; W2 giữ bậc 100 như M0.
    PLAN=(
      "w1 closed 50"
      "w2 fixed 200"
      "w2 fixed 400"
      "w2 capacity auto RATE_START=100 RATE_STEP=100 RATE_MAX=3000 MAX_VUS=1000"
      "w3 fixed 20 MAX_VUS=200"
      "w3 capacity auto RATE_START=10 RATE_STEP=20 RATE_MAX=600 MAX_VUS=300"
    )
    PROFILE_PLAN=("w2 fixed 400" "w3 fixed 20 MAX_VUS=200")
    ;;
  b3)
    CONFIGS=(C2 C2b3 C3 C3b3)
    DEFAULT_ARGS=([C2]="--diagramio.cache.mode=local --diagramio.cache.share-tokens=false"
                  [C2b3]="--diagramio.cache.mode=local"
                  [C3]="--diagramio.cache.mode=two-level --diagramio.cache.share-tokens=false"
                  [C3b3]="--diagramio.cache.mode=two-level")
    : "${REPS:=2}" "${SCALE:=1.0}" "${WARMUP:=180}" "${MEASURE:=180}"
    PLAN=(
      "w2 fixed 400"
      "w2 fixed 700"
      "w2 capacity auto RATE_START=100 RATE_STEP=100 RATE_MAX=3000 MAX_VUS=1000"
    )
    PROFILE_PLAN=("w2 fixed 400")
    ;;
  tune)
    CONFIGS=(C1 C1pool20 C1vt)
    DEFAULT_REF=([C1]=v2.1-b1 [C1pool20]=v2.1-b1 [C1vt]=v2.1-b1)
    DEFAULT_ARGS=([C1pool20]="--spring.datasource.hikari.maximum-pool-size=20"
                  [C1vt]="--spring.threads.virtual.enabled=true")
    : "${REPS:=2}" "${SCALE:=1.0}" "${WARMUP:=180}" "${MEASURE:=180}"
    PLAN=(
      "w2 fixed 400"
      "w2 capacity auto RATE_START=100 RATE_STEP=100 RATE_MAX=3000 MAX_VUS=1000"
    )
    PROFILE_PLAN=()
    ;;
  m0)
    CONFIGS=(C0)
    : "${REPS:=3}" "${SCALE:=1.0}" "${WARMUP:=60}" "${MEASURE:=180}"
    PLAN=(
      "w1 closed 10"
      "w1 closed 50"
      "w1 closed 100"
      "w2 fixed 200"
      "w2 fixed 500"
      "w2 fixed 800"
      "w2 capacity auto RATE_START=100 RATE_STEP=100 RATE_MAX=3000 MAX_VUS=1000"
      "w3 fixed 20 MAX_VUS=200"
      "w3 fixed 50 MAX_VUS=200"
      "w3 capacity auto RATE_START=10 RATE_STEP=10 RATE_MAX=400 MAX_VUS=200"
    )
    # Mỗi kịch bản chính có thêm một lần chạy lấy flame graph, không tính vào số liệu
    PROFILE_PLAN=("w2 fixed 500" "w3 fixed 50 MAX_VUS=200")
    ;;
  quick)
    # Cây làm việc hiện tại ở ba chế độ cache, để thử cả đường không cache, Caffeine và Redis
    CONFIGS=(C1 C2 C3)
    DEFAULT_ARGS=([C1]="--diagramio.cache.mode=none" [C2]="--diagramio.cache.mode=local"
                  [C3]="--diagramio.cache.mode=two-level")
    EXPLAIN=(C1)
    : "${REPS:=1}" "${SCALE:=0.02}" "${WARMUP:=10}" "${MEASURE:=20}"
    export PROFILE_SECONDS=${PROFILE_SECONDS:-10}
    PLAN=(
      "w1 closed 5"
      "w2 fixed 50 MAX_VUS=100 PRE_VUS=20"
      "w2 capacity auto RATE_START=20 RATE_STEP=20 RATE_MAX=100 STEP=10 SETTLE=2 MAX_VUS=100 PRE_VUS=20"
      "w3 fixed 5 MAX_VUS=10 PRE_VUS=10"
    )
    PROFILE_PLAN=("w2 fixed 50 MAX_VUS=100 PRE_VUS=20")
    ;;
  *)
    die "ma trận không có: $matrix (b12 | b3 | tune | m0 | quick)"
    ;;
esac
export WARMUP MEASURE

session="$RESULTS_DIR/$(date -u +%Y%m%d-%H%M%S)-$matrix"
mkdir -p "$session"
log "Phiên đo $session (ma trận $matrix, ${#CONFIGS[@]} cấu hình, $REPS lần lặp, scale $SCALE)"
bash "$BENCH_DIR/kaggle/env_report.sh" > "$session/env.txt" 2>&1

declare -A JARS ARGS BUILT
for cfg in "${CONFIGS[@]}"; do
  ref_var="REF_$cfg"
  args_var="ARGS_$cfg"
  ref=${!ref_var:-${DEFAULT_REF[$cfg]:-HEAD}}
  ARGS[$cfg]=${!args_var-${DEFAULT_ARGS[$cfg]:-}}
  if [ "$matrix" = quick ] && [ -z "${!ref_var:-}" ]; then
    ref=WORKTREE
  fi
  if [ -z "${BUILT[$ref]:-}" ]; then
    log "Build $cfg từ $ref"
    BUILT[$ref]=$(bash "$BENCH_DIR/kaggle/build.sh" "$ref")
  fi
  JARS[$cfg]=${BUILT[$ref]}
  echo "$cfg $ref $(cat "$(dirname "${JARS[$cfg]}")/git-sha") ${ARGS[$cfg]}" >> "$session/configs.txt"
done

# Schema của CSDL mẫu do cấu hình đầu tiên (mốc cũ nhất) tạo; cấu hình sau có migration mới thì Flyway
# tự chạy thêm khi app khởi động trên bản chép
bash "$BENCH_DIR/kaggle/prepare_data.sh" "${JARS[${CONFIGS[0]}]}" "$SCALE"
cp "$DATASET_DIR/manifest.json" "$DATASET_DIR/db-sizes.txt" "$session/"

for cfg in "${EXPLAIN[@]}"; do
  bash "$BENCH_DIR/kaggle/explain.sh" "$cfg" "${JARS[$cfg]}" "$session" \
    || log "CẢNH BÁO: EXPLAIN của $cfg lỗi, chạy tiếp phép đo"
done

run_entry() {  # run_entry <rep> <cfg> <entry> [--profile]
  local rep=$1 cfg=$2 entry=$3 extra=${4:-}
  local workload mode level vars
  read -r workload mode level vars <<< "$entry"
  # shellcheck disable=SC2086 # vars cố ý được tách thành nhiều cặp TÊN=giá_trị
  env $vars APP_ARGS="${ARGS[$cfg]}" bash "$BENCH_DIR/kaggle/run_one.sh" --config "$cfg" --jar "${JARS[$cfg]}" \
    --workload "$workload" --mode "$mode" --level "$level" --rep "$rep" --session "$session" $extra \
    || log "CẢNH BÁO: lần chạy $cfg $entry (lặp $rep) lỗi, chạy tiếp các lần khác"
}

total=$(( REPS * ${#PLAN[@]} * ${#CONFIGS[@]} + ${#PROFILE_PLAN[@]} * ${#CONFIGS[@]} ))
done_runs=0
started=$(date +%s)
for rep in $(seq 1 "$REPS"); do
  for entry in "${PLAN[@]}"; do
    for cfg in "${CONFIGS[@]}"; do
      run_entry "$rep" "$cfg" "$entry"
      done_runs=$((done_runs + 1))
      elapsed=$(( $(date +%s) - started ))
      log "Tiến độ $done_runs/$total, đã chạy $((elapsed / 60)) phút"
    done
  done
done
for entry in "${PROFILE_PLAN[@]}"; do
  for cfg in "${CONFIGS[@]}"; do
    run_entry prof "$cfg" "$entry" --profile
  done
done

log "Tổng hợp báo cáo"
"$PYTHON" "$BENCH_DIR/analysis/report.py" "$session" || log "CẢNH BÁO: report.py lỗi, số liệu thô vẫn còn trong $session"
log "Xong phiên $session sau $(( ($(date +%s) - started) / 60 )) phút"
