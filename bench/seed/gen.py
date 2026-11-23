#!/usr/bin/env python3
"""Sinh dữ liệu đo hiệu năng cho DiagramIO, xác định hoàn toàn theo seed.

Đầu ra (thư mục --out):
  csv/*.tsv          dữ liệu cho lệnh COPY (định dạng text của PostgreSQL), nạp bằng bench/kaggle/prepare_data.sh
  fixtures/*.json    danh sách user, sơ đồ, token... cho kịch bản k6 (bench/k6)
  manifest.json      số dòng, phân bố kích thước, SHA-256 từng file để chứng minh hai lần sinh giống hệt nhau

Cùng seed và scale thì mọi file giống hệt nhau từng byte trên mọi máy: mỗi thực thể có bộ sinh số ngẫu nhiên
riêng, dẫn xuất từ seed bằng SHA-256, nên kết quả không phụ thuộc thứ tự sinh hay phiên bản Python 3.
Chỉ dùng thư viện chuẩn.

Ví dụ: python3 bench/seed/gen.py --out /kaggle/tmp/dataset --scale 1.0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import random
import sys
import time
from datetime import datetime, timedelta, timezone

GENERATOR_VERSION = 1
DEFAULT_SEED = 20261123

# Quy mô ở scale = 1.0 (docs/ke-hoach-pha-2.md mục 3.2)
BASE_USERS = 2_000
BASE_DIAGRAMS = 50_000
BASE_SHARES = 20_000
BASE_LONG_HISTORY = 50          # sơ đồ nhỏ có 200-400 phiên bản

# Kích thước data: 80% ~5 KB, 18% ~50 KB, 2% 500 KB - 1,5 MB
SIZE_CLASSES = (("S", 0.80, 3_000, 7_000), ("M", 0.18, 35_000, 65_000), ("L", 0.02, 500_000, 1_500_000))
# Số phiên bản trung bình theo cỡ sơ đồ: tổng ~150k ở scale 1, sơ đồ lớn ít phiên bản để dữ liệu vừa đĩa
MEAN_VERSIONS = {"S": 3.0, "M": 1.5, "L": 0.5}
LONG_HISTORY_RANGE = (200, 400)
PUBLIC_RATIO = 0.10
EDIT_SHARE_RATIO = 0.30
OWNER_ZIPF_S = 0.9              # vài user có 1.000+ sơ đồ ở scale 1
# Byte trung bình của một node kèm một edge (đo từ chính hàm sinh bên dưới), để chọn số node theo kích thước
AVG_NODE_BYTES = {"ERD": 407, "UML": 647}

# Tài khoản bench dùng chung hash BCrypt của tài khoản mẫu trong V2__seed_sample_user.sql (mật khẩu uet123456)
PASSWORD = "uet123456"
PASSWORD_HASH = "$2a$10$jqmc./tQysLyXTNGg8M4CeIlAO5GffLskOQWPRLjPYfLidU3fVWEO"

BASE_TIME = datetime(2026, 1, 1, tzinfo=timezone.utc)

WORDS = ["Customer", "Order", "Product", "Invoice", "Payment", "Shipment", "Supplier", "Warehouse",
         "Employee", "Department", "Course", "Student", "Teacher", "Enrollment", "Room", "Booking",
         "Hotel", "Flight", "Ticket", "Patient", "Doctor", "Appointment", "Account", "Transaction",
         "Branch", "Book", "Author", "Loan", "Member", "Category", "Review", "Cart",
         "Coupon", "Address", "Vehicle", "Driver", "Route", "Project", "Task", "Sprint"]
COLUMNS = ["name", "code", "status", "created_at", "updated_at", "email", "phone", "price", "quantity",
           "total", "note", "description", "address", "start_date", "end_date", "amount", "level", "rating"]
TYPES = ["varchar(255)", "integer", "bigint", "numeric(12,2)", "timestamptz", "boolean", "text", "uuid", "date"]
VERBS = ["create", "update", "delete", "find", "validate", "calculate", "notify", "export", "approve"]
ERD_EDGES = ["one-to-one", "one-to-many", "many-to-many"]
UML_EDGES = ["association", "aggregation", "composition", "inheritance", "dependency", "realization"]
NONCE = "__NONCE__"


def derive_rng(seed: int, *parts: object) -> random.Random:
    """Bộ sinh riêng cho một thực thể: đổi cách sinh thực thể này không làm lệch các thực thể khác."""
    key = "/".join([str(seed), *map(str, parts)]).encode()
    return random.Random(int.from_bytes(hashlib.sha256(key).digest()[:8], "big"))


def uuid4_from(rng: random.Random) -> str:
    value = rng.getrandbits(128)
    value = (value & ~(0xF << 76)) | (0x4 << 76)       # version 4
    value = (value & ~(0x3 << 62)) | (0x2 << 62)       # variant RFC 4122
    h = f"{value:032x}"
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def ts(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%d %H:%M:%S+00")


def copy_text(value: object) -> str:
    """Một ô theo định dạng text của COPY: NULL là \\N, thoát \\, tab và xuống dòng."""
    if value is None:
        return "\\N"
    text = value if isinstance(value, str) else str(value)
    if "\\" in text or "\t" in text or "\n" in text or "\r" in text:
        text = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    return text


def compact(value: object) -> str:
    return json.dumps(value, separators=(",", ":"))


def erd_node(rng: random.Random, i: int) -> dict:
    n = rng.randint(2, 8)
    attributes = [{"name": "id", "type": "uuid", "pk": True}]
    attributes += [{"name": rng.choice(COLUMNS), "type": rng.choice(TYPES)} for _ in range(n)]
    return {"id": f"n{i}", "type": "entity", "label": f"{rng.choice(WORDS)}{i}",
            "x": (i % 20) * 240, "y": (i // 20) * 200, "width": 200, "height": 40 + 22 * (n + 1),
            "attributes": attributes}


def uml_node(rng: random.Random, i: int) -> dict:
    n, m = rng.randint(2, 6), rng.randint(1, 4)
    attributes = [{"name": rng.choice(COLUMNS), "type": rng.choice(TYPES), "visibility": rng.choice("+-#")}
                  for _ in range(n)]
    methods = [{"name": rng.choice(VERBS) + rng.choice(WORDS), "returns": rng.choice(WORDS),
                "params": [rng.choice(COLUMNS) + ": " + rng.choice(TYPES)], "visibility": "+"} for _ in range(m)]
    return {"id": f"n{i}", "type": "class", "label": f"{rng.choice(WORDS)}Service{i}",
            "x": (i % 20) * 240, "y": (i // 20) * 200, "width": 220, "height": 60 + 20 * (n + m),
            "attributes": attributes, "methods": methods}


class Content:
    """Nội dung một sơ đồ: node đã serialize sẵn từng cái, để mỗi phiên bản chỉ serialize lại node bị sửa."""

    def __init__(self, rng: random.Random, kind: str, target_bytes: int):
        count = max(2, round(target_bytes / AVG_NODE_BYTES[kind]))
        make = erd_node if kind == "ERD" else uml_node
        edge_types = ERD_EDGES if kind == "ERD" else UML_EDGES
        self.nodes = [make(rng, i) for i in range(count)]
        self.node_json = [compact(node) for node in self.nodes]
        edges = [{"id": f"e{i}", "source": f"n{rng.randrange(i)}", "target": f"n{i}",
                  "type": rng.choice(edge_types), "label": rng.choice(VERBS)} for i in range(1, count)]
        self.edges_json = compact(edges)

    def json(self, node_json: list[str] | None = None) -> str:
        return '{"nodes":[' + ",".join(node_json or self.node_json) + '],"edges":' + self.edges_json + "}"

    def revision(self, rng: random.Random, number: int) -> str:
        """Một phiên bản cũ: khoảng 2% node bị đổi nhãn và vị trí."""
        node_json = list(self.node_json)
        for _ in range(max(1, len(self.nodes) // 50)):
            index = rng.randrange(len(self.nodes))
            node = dict(self.nodes[index])
            node["label"] = f"{node['label']}_v{number}"
            node["x"] = node["x"] + rng.randint(-40, 40)
            node_json[index] = compact(node)
        return self.json(node_json)


def pick_class(rng: random.Random) -> tuple[str, int]:
    r = rng.random()
    for name, share, low, high in SIZE_CLASSES:
        if r < share:
            return name, rng.randint(low, high)
        r -= share
    name, _, low, high = SIZE_CLASSES[-1]
    return name, rng.randint(low, high)


def geometric(rng: random.Random, mean: float) -> int:
    """Phân bố hình học trên 0, 1, 2... với trung bình cho trước.

    Thử Bernoulli lặp lại thay vì công thức nghịch đảo dùng log: chỉ có phép so sánh số thực chính xác,
    nên kết quả giống nhau từng bit trên mọi nền tảng (thư viện toán của mỗi hệ điều hành có thể lệch 1 ULP).
    """
    if mean <= 0:
        return 0
    p = 1.0 / (1.0 + mean)
    count = 0
    while rng.random() >= p:
        count += 1
    return count


class Writer:
    """Ghi file và tính SHA-256 trong lúc ghi."""

    def __init__(self, root: str, relative: str):
        self.relative = relative
        self.path = os.path.join(root, relative)
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.file = open(self.path, "wb", buffering=1 << 20)
        self.digest = hashlib.sha256()
        self.size = 0
        self.rows = 0

    def row(self, *cells: object) -> None:
        self.rows += 1
        self._write(("\t".join(copy_text(cell) for cell in cells) + "\n").encode("utf-8"))

    def text(self, content: str) -> None:
        self._write(content.encode("utf-8"))

    def _write(self, data: bytes) -> None:
        self.digest.update(data)
        self.size += len(data)
        self.file.write(data)

    def close(self) -> dict:
        self.file.close()
        return {"bytes": self.size, "rows": self.rows, "sha256": self.digest.hexdigest()}


def summarize(values: list[int]) -> dict:
    if not values:
        return {"count": 0}
    ordered = sorted(values)

    def q(p: float) -> int:
        return ordered[min(len(ordered) - 1, int(p * len(ordered)))]

    return {"count": len(ordered), "mean": round(sum(ordered) / len(ordered)), "min": ordered[0],
            "p50": q(0.50), "p90": q(0.90), "p99": q(0.99), "max": ordered[-1]}


def payload_templates(seed: int) -> dict[str, list[str]]:
    """Body data cho PUT trong k6: vài mẫu mỗi cỡ, node đầu tiên có nhãn __NONCE__ để k6 thay bằng chuỗi riêng."""
    templates: dict[str, list[str]] = {}
    sizes = {"tiny": [(1_000, "ERD")] * 2,
             "S": [(5_000, "ERD"), (5_000, "UML"), (4_000, "ERD"), (6_000, "UML")],
             "M": [(50_000, "ERD"), (50_000, "UML"), (40_000, "ERD"), (60_000, "UML")],
             "L": [(1_000_000, "ERD"), (800_000, "UML"), (1_200_000, "ERD")]}
    for name, specs in sizes.items():
        templates[name] = []
        for k, (target, kind) in enumerate(specs):
            content = Content(derive_rng(seed, "payload", name, k), kind, target)
            node = dict(content.nodes[0])
            node["label"] = NONCE
            content.node_json[0] = compact(node)
            templates[name].append(content.json())
    return templates


def generate(out: str, scale: float, seed: int, active_users: int, log) -> dict:
    started = time.time()
    master = random.Random(seed)
    n_users = max(10, round(BASE_USERS * scale))
    n_diagrams = max(50, round(BASE_DIAGRAMS * scale))
    n_shares = max(20, round(BASE_SHARES * scale))
    n_long = max(1, round(BASE_LONG_HISTORY * scale))
    n_active = min(active_users, n_users)

    # ---- Người dùng: user 0 có nhiều sơ đồ nhất (Zipf), nên "người dùng đang hoạt động" là các user đầu tiên
    users = []
    w_users = Writer(out, "csv/users.tsv")
    for i in range(n_users):
        rng = derive_rng(seed, "user", i)
        user_id = uuid4_from(rng)
        created = BASE_TIME + timedelta(minutes=i)
        users.append({"id": user_id, "email": f"bench{i:05d}@bench.diagramio.test"})
        w_users.row(user_id, f"bench{i:05d}", users[-1]["email"], PASSWORD_HASH, ts(created), ts(created))

    # ---- Thuộc tính của từng sơ đồ, quyết định trước bằng bộ sinh chính (thứ tự cố định)
    owner_weights = [1.0 / (k + 1) ** OWNER_ZIPF_S for k in range(n_users)]
    owners = master.choices(range(n_users), weights=owner_weights, k=n_diagrams)
    owned_counts = [0] * n_users
    for owner in owners:
        owned_counts[owner] += 1
    popularity = list(range(n_diagrams))
    master.shuffle(popularity)                     # popularity[i] = hạng phổ biến của sơ đồ i (0 = nóng nhất)
    meta = []
    for i in range(n_diagrams):
        size_class, target = pick_class(master)
        meta.append({"i": i, "owner": owners[i], "class": size_class, "target": target,
                     "kind": "ERD" if master.random() < 0.55 else "UML",
                     "public": master.random() < PUBLIC_RATIO, "rank": popularity[i]})
    # Sơ đồ có lịch sử dài: sơ đồ nhỏ của người dùng đang hoạt động, để kịch bản đọc lịch sử chạm tới
    long_candidates = [m["i"] for m in meta if m["class"] == "S" and m["owner"] < n_active]
    long_history = set(master.sample(long_candidates, min(n_long, len(long_candidates))))

    # ---- Sơ đồ và phiên bản, sinh lần lượt từng sơ đồ để không giữ data trong bộ nhớ
    w_diagrams = Writer(out, "csv/diagrams.tsv")
    w_versions = Writer(out, "csv/diagram_versions.tsv")
    data_sizes: dict[str, list[int]] = {"S": [], "M": [], "L": []}
    version_counts: list[int] = []
    version_bytes = 0
    for m in meta:
        rng = derive_rng(seed, "diagram", m["i"])
        diagram_id = uuid4_from(rng)
        owner_id = users[m["owner"]]["id"]
        word_a, word_b = rng.choice(WORDS), rng.choice(WORDS)
        title = f"{word_a} {word_b} {m['kind']} {m['i']}"
        created = BASE_TIME + timedelta(minutes=rng.randrange(300 * 24 * 60))
        content = Content(rng, m["kind"], m["target"])
        data = content.json()
        data_sizes[m["class"]].append(len(data))

        if m["i"] in long_history:
            count = rng.randint(*LONG_HISTORY_RANGE)
        else:
            count = geometric(rng, MEAN_VERSIONS[m["class"]])
        version_counts.append(count)
        version_ids = []
        for number in range(1, count + 1):
            version_id = uuid4_from(rng)
            version_ids.append(version_id)
            revision = content.revision(rng, number)
            version_bytes += len(revision)
            w_versions.row(version_id, diagram_id, number, revision, f"v{number}", owner_id,
                           ts(created + timedelta(hours=number)))
        updated = created + timedelta(hours=count + 1 + rng.randrange(48))
        lock_version = 1 + count + rng.randrange(20)
        w_diagrams.row(diagram_id, title, m["kind"], data, lock_version, "t" if m["public"] else "f",
                       owner_id, owner_id, ts(created), ts(updated))
        m.update(id=diagram_id, title=title, version=lock_version, versions=count,
                 restore=version_ids[rng.randrange(count)] if count else None)
        if m["i"] % 5000 == 0:
            log(f"  {m['i']:>6}/{n_diagrams} sơ đồ, {time.time() - started:.0f}s")

    # ---- Link chia sẻ: sơ đồ càng phổ biến càng hay được chia sẻ (một sơ đồ có thể có nhiều link)
    share_weights = [1.0 / (m["rank"] + 10) ** 0.8 for m in meta]
    shared = master.choices(range(n_diagrams), weights=share_weights, k=n_shares)
    w_shares = Writer(out, "csv/diagram_shares.tsv")
    shares = []
    for k, diagram_index in enumerate(shared):
        rng = derive_rng(seed, "share", k)
        share_id = uuid4_from(rng)
        token = f"{rng.getrandbits(192):048x}"
        role = "edit" if rng.random() < EDIT_SHARE_RATIO else "view"
        m = meta[diagram_index]
        w_shares.row(share_id, m["id"], token, role, ts(BASE_TIME + timedelta(minutes=rng.randrange(300 * 24 * 60))))
        shares.append({"tk": token, "d": m["id"], "r": role, "c": m["class"], "rank": m["rank"]})

    files = {w.relative: w.close() for w in (w_users, w_diagrams, w_versions, w_shares)}

    # ---- Fixture cho k6, mỗi danh sách xếp theo độ phổ biến (phần tử đầu là nóng nhất)
    by_rank = sorted(meta, key=lambda m: m["rank"])
    owned = [m for m in by_rank if m["owner"] < n_active]
    fixtures = {
        "users": [{"i": i, "id": users[i]["id"], "email": users[i]["email"], "n": owned_counts[i]}
                  for i in range(n_active)],
        "diagrams": [{"id": m["id"], "o": m["owner"], "t": m["title"], "ty": m["kind"], "p": m["public"],
                      "v": m["version"], "c": m["class"], "r": m["restore"]} for m in owned],
        "history": [{"id": m["id"], "o": m["owner"], "r": m["restore"], "c": m["class"], "n": m["versions"],
                     "v": m["version"]} for m in owned if m["versions"] > 0],
        "public": [{"id": m["id"], "c": m["class"]} for m in by_rank if m["public"]],
        "shares": [{"tk": s["tk"], "d": s["d"], "r": s["r"], "c": s["c"]}
                   for s in sorted(shares, key=lambda s: s["rank"])],
        "words": [w.lower() for w in WORDS],
        "payloads": payload_templates(seed),
    }
    for name, value in fixtures.items():
        w = Writer(out, f"fixtures/{name}.json")
        w.text(json.dumps(value, separators=(",", ":")))
        files[w.relative] = w.close()

    manifest = {
        "generator": "bench/seed/gen.py",
        "generator_version": GENERATOR_VERSION,
        "seed": seed,
        "scale": scale,
        "python": platform.python_version(),
        "password": PASSWORD,
        "active_users": n_active,
        "counts": {"users": n_users, "diagrams": n_diagrams, "diagram_versions": sum(version_counts),
                   "diagram_shares": n_shares, "public_diagrams": sum(1 for m in meta if m["public"]),
                   "long_history_diagrams": len(long_history)},
        "data_bytes": {"diagrams": sum(sum(v) for v in data_sizes.values()), "diagram_versions": version_bytes},
        "data_size_by_class": {name: summarize(values) for name, values in data_sizes.items()},
        "versions_per_diagram": summarize(version_counts),
        "diagrams_per_owner": summarize(owned_counts),
        "owners_with_1000_plus": sum(1 for c in owned_counts if c >= 1000),
        "fixture_sizes": {name: len(value) for name, value in fixtures.items() if isinstance(value, list)},
        "files": files,
        "seconds": round(time.time() - started, 1),
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="thư mục đầu ra (được tạo nếu chưa có)")
    parser.add_argument("--scale", type=float, default=1.0, help="hệ số quy mô, 1.0 = 2.000 user, 50.000 sơ đồ")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--active-users", type=int, default=200,
                        help="số user (nhiều sơ đồ nhất) mà kịch bản k6 đăng nhập và thao tác")
    args = parser.parse_args()
    if args.scale <= 0:
        parser.error("--scale phải lớn hơn 0")

    def log(message: str) -> None:
        print(message, file=sys.stderr, flush=True)

    log(f"gen.py: scale={args.scale} seed={args.seed} -> {args.out}")
    manifest = generate(args.out, args.scale, args.seed, args.active_users, log)
    counts = manifest["counts"]
    log(f"gen.py: xong trong {manifest['seconds']}s: {counts['users']} user, {counts['diagrams']} sơ đồ, "
        f"{counts['diagram_versions']} phiên bản, {counts['diagram_shares']} link; "
        f"data {manifest['data_bytes']['diagrams'] / 2**20:.0f} MB + "
        f"{manifest['data_bytes']['diagram_versions'] / 2**20:.0f} MB phiên bản")
    return 0


if __name__ == "__main__":
    sys.exit(main())
