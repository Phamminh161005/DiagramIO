#!/usr/bin/env bash
# Build file jar của một phiên bản mã nguồn và in đường dẫn jar ra stdout (log ra stderr).
#
#   bash bench/kaggle/build.sh <ref>        ref: tag, nhánh hoặc commit, ví dụ v2.0-m0
#   bash bench/kaggle/build.sh WORKTREE     cây làm việc hiện tại kể cả thay đổi chưa commit (chỉ để thử)
#
# Mã nguồn được xuất bằng "git archive" vào thư mục riêng, không đụng tới checkout đang dùng. Jar được lưu theo
# commit SHA nên build lại cùng commit thì dùng lại.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
exec 3>&1 1>&2   # mọi log ra stderr, stdout chỉ để in đường dẫn jar

ref=${1:?"cách dùng: build.sh <ref>|WORKTREE"}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ "$ref" = WORKTREE ]; then
  sha="worktree-$(date +%Y%m%d%H%M%S)"
else
  sha=$(git -C "$REPO_DIR" rev-parse --verify "$ref^{commit}") || die "không có ref $ref"
fi
out="$WORK_DIR/builds/$sha"
if [ -f "$out/app.jar" ]; then
  log "Dùng lại bản build $sha"
  echo "$out/app.jar" >&3
  exit 0
fi

src="$WORK_DIR/src/$sha"
rm -rf "$src" && mkdir -p "$src" "$out"
if [ "$ref" = WORKTREE ]; then
  log "Chép cây làm việc hiện tại (kể cả thay đổi chưa commit)"
  git -C "$REPO_DIR" ls-files -z --cached --others --exclude-standard \
    | (cd "$REPO_DIR" && tar --null -T - -cf - 2>/dev/null) | tar -xf - -C "$src"
  # Checkout trên Windows có thể mang CRLF, làm hỏng script shell khi chạy trên Linux
  sed -i 's/\r$//' "$src/mvnw"
  git -C "$REPO_DIR" rev-parse HEAD > "$out/base-commit" 2>/dev/null || true
else
  log "Xuất mã nguồn $ref ($sha)"
  git -C "$REPO_DIR" archive "$sha" | tar -xf - -C "$src"
fi

log "Build jar (mvn package, bỏ qua test)"
(cd "$src" && bash ./mvnw -B -q -ntp -DskipTests package)
jar=$(find "$src/target" -maxdepth 1 -name 'diagramio-*.jar' | head -n 1)
[ -n "$jar" ] || die "không thấy file jar sau khi build"
cp "$jar" "$out/app.jar"
echo "$sha" > "$out/git-sha"
rm -rf "$src"
log "Xong: $out/app.jar"
echo "$out/app.jar" >&3
