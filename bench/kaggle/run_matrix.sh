#!/usr/bin/env bash
# Chạy một ma trận đo trọn vẹn trong một phiên: ghi cấu hình máy, build jar từng cấu hình, tạo CSDL mẫu,
# chạy mọi phép đo (lặp REPS lần, xen kẽ các cấu hình trong mỗi vòng), cuối cùng tổng hợp báo cáo.
#
#   bash bench/kaggle/run_matrix.sh [m0|quick]
#
# m0     cấu hình C0 (hiện trạng), kịch bản W1-W3, mỗi phép đo 3 lần: khoảng 3-4 giờ trên Kaggle
# quick  chạy thử vài phút với dữ liệu nhỏ, để kiểm tra môi trường trước khi chạy thật
#
# Biến môi trường: REF_C0 (git ref của C0, mặc định HEAD), REPS, SCALE, WARMUP, MEASURE, RESULTS_DIR.
# Kết quả: $RESULTS_DIR/<thời điểm>-<ma trận>/ gồm env.txt, manifest.json, mỗi lần chạy một thư mục, report.md.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

matrix=${1:-m0}
case "$matrix" in
  m0)
    CONFIGS=(C0)
    : "${REPS:=3}" "${SCALE:=1.0}" "${WARMUP:=60}" "${MEASURE:=180}"
    # workload mode level [biến môi trường riêng cho lần chạy]
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
    CONFIGS=(C0)
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
    die "ma trận không có: $matrix (m0 | quick)"
    ;;
esac
export WARMUP MEASURE

session="$RESULTS_DIR/$(date -u +%Y%m%d-%H%M%S)-$matrix"
mkdir -p "$session"
log "Phiên đo $session (ma trận $matrix, ${#CONFIGS[@]} cấu hình, $REPS lần lặp, scale $SCALE)"
bash "$BENCH_DIR/kaggle/env_report.sh" > "$session/env.txt" 2>&1

declare -A JARS
for cfg in "${CONFIGS[@]}"; do
  ref_var="REF_$cfg"
  ref=${!ref_var:-HEAD}
  log "Build $cfg từ $ref"
  JARS[$cfg]=$(bash "$BENCH_DIR/kaggle/build.sh" "$ref")
  echo "$cfg $ref $(cat "$(dirname "${JARS[$cfg]}")/git-sha")" >> "$session/configs.txt"
done

# Schema của CSDL mẫu do cấu hình đầu tiên (hiện trạng) tạo; cấu hình sau có migration mới thì Flyway
# tự chạy thêm khi app khởi động trên bản chép
bash "$BENCH_DIR/kaggle/prepare_data.sh" "${JARS[${CONFIGS[0]}]}" "$SCALE"
cp "$DATASET_DIR/manifest.json" "$DATASET_DIR/db-sizes.txt" "$session/"

run_entry() {  # run_entry <rep> <cfg> <entry> [--profile]
  local rep=$1 cfg=$2 entry=$3 extra=${4:-}
  local workload mode level vars
  read -r workload mode level vars <<< "$entry"
  # shellcheck disable=SC2086 # vars cố ý được tách thành nhiều cặp TÊN=giá_trị
  env $vars bash "$BENCH_DIR/kaggle/run_one.sh" --config "$cfg" --jar "${JARS[$cfg]}" --workload "$workload" \
    --mode "$mode" --level "$level" --rep "$rep" --session "$session" $extra \
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
