// W2: hỗn hợp thực tế ~85% đọc / 15% ghi trên dữ liệu bench (docs/ke-hoach-pha-2.md mục 3.3).
// W3: cùng kịch bản nhưng chỉ thao tác trên sơ đồ 500 KB - 1,5 MB: chạy với -e SIZE_CLASS=L -e WORKLOAD=w3.
//
// Mỗi iteration là đúng một request, nên tốc độ đến (iteration/giây) của mô hình tải mở chính là request/giây.
// Thao tác ghi của mỗi VU chỉ chạm các sơ đồ trong phân vùng riêng của VU đó, nên không có hai VU sửa cùng
// một sơ đồ và W2 không sinh 409 giả tạo; VU tự nhớ version của sơ đồ mình sửa (mỗi lần sửa hay khôi phục +1).
// Tranh chấp thật (nhiều người sửa một sơ đồ) là việc của W4.
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { FIXTURES, MAX_VUS, PAGE_SIZE, SIZE_CLASS } from './lib/config.js';
import { fixture, payloads, pick, uniform, weighted, zipfIndex } from './lib/data.js';
import { call, loginAll } from './lib/api.js';
import { buildOptions, summary } from './lib/run.js';

const WORKLOAD = __ENV.WORKLOAD || (SIZE_CLASS === 'L' ? 'w3' : 'w2');

// Tỉ lệ thao tác (tổng 100): 85 đọc, 15 ghi. Đổi bằng -e MIX='{"open_owner":30,...}'
const DEFAULT_MIX = {
  open_owner: 25,     // chủ mở sơ đồ
  open_revisit: 10,   // chủ mở lại, gửi If-None-Match nếu lần trước có ETag
  share_open: 12,     // mở link chia sẻ GET /shares/{token}
  share_header: 10,   // người cầm link đọc sơ đồ kèm X-Share-Token
  public_read: 5,     // khách đọc sơ đồ công khai
  list_mine: 6,       // danh sách sơ đồ của tôi
  search_mine: 4,     // tìm theo tên
  list_public: 5,     // danh sách sơ đồ công khai
  history: 6,         // danh sách phiên bản
  version_get: 2,     // xem một phiên bản
  put: 8,             // sửa sơ đồ
  snapshot: 3,        // lưu phiên bản
  restore: 2,         // khôi phục phiên bản
  share_create: 2,    // tạo link chia sẻ
};
const MIX = __ENV.MIX ? JSON.parse(__ENV.MIX) : DEFAULT_MIX;
const OPS = Object.keys(MIX);

const users = fixture('users');
const diagrams = fixture('diagrams', { bySize: true });
const history = fixture('history', { bySize: true });
const shares = fixture('shares', { bySize: true });
const publics = fixture('public', { bySize: true });
const words = fixture('words');
const templates = { S: payloads('S'), M: payloads('M'), L: payloads('L') };

// Phân vùng ghi: sơ đồ thứ i (theo độ phổ biến) thuộc phân vùng i % PARTITIONS, VU n dùng phân vùng (n-1) % PARTITIONS
const PARTITIONS = Math.max(1, Math.min(MAX_VUS, diagrams.length));
const buckets = new SharedArray(`buckets:${SIZE_CLASS}:${PARTITIONS}`, () => {
  const keep = (item) => SIZE_CLASS === '' || item.c === SIZE_CLASS;
  const ds = JSON.parse(open(`${FIXTURES}/diagrams.json`)).filter(keep);
  const hs = JSON.parse(open(`${FIXTURES}/history.json`)).filter(keep);
  const result = [];
  for (let p = 0; p < PARTITIONS; p++) {
    result.push({ d: [], h: [] });
  }
  // Sơ đồ có lịch sử được chia đều trước (để phân vùng nào cũng có thứ để khôi phục), rồi tới các sơ đồ còn lại
  const partitionOf = {};
  for (let i = 0; i < hs.length; i++) {
    partitionOf[hs[i].id] = i % PARTITIONS;
    result[i % PARTITIONS].h.push(i);
  }
  let next = 0;
  for (let i = 0; i < ds.length; i++) {
    if (!(ds[i].id in partitionOf)) {
      partitionOf[ds[i].id] = next++ % PARTITIONS;
    }
    result[partitionOf[ds[i].id]].d.push(i);
  }
  return result;
});

const conflicts = new Counter('write_conflicts');      // 409 ngoài dự kiến: lỗi của bộ đo
const resyncs = new Counter('write_resyncs');          // đọc lại version sau một lần ghi không rõ kết quả
const fallbacks = new Counter('op_fallbacks');

export const options = buildOptions(OPS);

export function setup() {
  if (PARTITIONS < MAX_VUS) {
    console.warn(`chỉ có ${PARTITIONS} sơ đồ để ghi cho tới ${MAX_VUS} VU: VU có thể dùng chung phân vùng và `
      + 'gây 409 giả tạo (write_conflicts); giảm MAX_VUS hoặc tăng SCALE');
  }
  if (history.length < PARTITIONS) {
    console.warn(`chỉ có ${history.length} sơ đồ có lịch sử cho ${PARTITIONS} phân vùng: một số VU không có gì `
      + 'để khôi phục (op_fallbacks); giảm MAX_VUS');
  }
  const list = [];
  for (let i = 0; i < users.length; i++) {
    list.push(users[i]);
  }
  return { tokens: loginAll(list) };
}

// ---- Trạng thái riêng của mỗi VU
let bucket = null;
const lockVersions = {};   // id sơ đồ -> version hiện tại (chỉ các sơ đồ trong phân vùng của VU này)
let etags = {};            // id sơ đồ -> ETag của lần mở trước
let etagCount = 0;
let nonce = 0;
// Sơ đồ có lần ghi không nhận được phản hồi (timeout, lỗi 5xx): không biết server đã ghi hay chưa, nên phải đọc
// lại version trước lần ghi kế tiếp, thay vì gửi version cũ rồi nhận 409
const uncertain = {};

function myBucket() {
  if (bucket === null) {
    bucket = buckets[(exec.vu.idInTest - 1) % PARTITIONS];
  }
  return bucket;
}

function versionOf(item) {
  if (!(item.id in lockVersions)) {
    lockVersions[item.id] = item.v;
  }
  return lockVersions[item.id];
}

function rememberEtag(id, etag) {
  if (etagCount >= 5000) {
    etags = {};
    etagCount = 0;
  }
  if (!(id in etags)) {
    etagCount++;
  }
  etags[id] = etag;
}

function resync(id, ownerIndex, data) {
  const res = call('GET', '/diagrams/<id>', `/diagrams/${id}`, { op: 'resync', token: data.tokens[ownerIndex], readBody: true });
  if (res.status === 200) {
    lockVersions[id] = res.json('version');
    delete uncertain[id];
  }
}

/** Trước khi ghi: nếu lần ghi trước của sơ đồ này không rõ kết quả thì đọc lại version. */
function settle(id, ownerIndex, data) {
  if (uncertain[id]) {
    resyncs.add(1);
    resync(id, ownerIndex, data);
  }
}

/**
 * Sau khi ghi một thao tác làm tăng version: 200 thì +1; 409 là ngoài dự kiến (VU nhớ sai version, hoặc server so
 * với một bản cũ trong cache như phiên b12 ở C3); không có phản hồi thì chưa chắc.
 */
function afterVersionedWrite(res, id, ownerIndex, data) {
  if (res.status === 200) {
    lockVersions[id]++;
  } else if (res.status === 409) {
    conflicts.add(1);
    resync(id, ownerIndex, data);
  } else if (res.status === 0 || res.status >= 500) {
    uncertain[id] = true;
  }
}

/** Danh sách rỗng (ví dụ W3 ở quy mô nhỏ không có sơ đồ lớn công khai): đọc sơ đồ của chủ thay thế. */
function fallback(data) {
  fallbacks.add(1);
  OPERATIONS.open_owner(data);
}

const OPERATIONS = {
  open_owner(data) {
    const d = pick(diagrams);
    call('GET', '/diagrams/<id>', `/diagrams/${d.id}`, { op: 'open_owner', token: data.tokens[d.o] });
  },
  open_revisit(data) {
    const d = pick(diagrams);
    const res = call('GET', '/diagrams/<id>', `/diagrams/${d.id}`, {
      op: 'open_revisit', token: data.tokens[d.o], ifNoneMatch: etags[d.id] || null,
    });
    const etag = res.headers.Etag;
    if (etag) {
      rememberEtag(d.id, etag);
    }
  },
  share_open(data) {
    if (shares.length === 0) {
      return fallback(data);
    }
    const s = pick(shares);
    call('GET', '/shares/<token>', `/shares/${s.tk}`, { op: 'share_open' });
  },
  share_header(data) {
    if (shares.length === 0) {
      return fallback(data);
    }
    const s = pick(shares);
    call('GET', '/diagrams/<id>', `/diagrams/${s.d}`, { op: 'share_header', shareToken: s.tk });
  },
  public_read(data) {
    if (publics.length === 0) {
      return fallback(data);
    }
    const p = pick(publics);
    call('GET', '/diagrams/<id>', `/diagrams/${p.id}`, { op: 'public_read' });
  },
  list_mine(data) {
    const u = pick(users);
    call('GET', '/diagrams', `/diagrams?page=0&size=${PAGE_SIZE}`, { op: 'list_mine', token: data.tokens[u.i] });
  },
  search_mine(data) {
    const u = pick(users);
    call('GET', '/diagrams?title', `/diagrams?title=${uniform(words)}&page=0&size=${PAGE_SIZE}`, {
      op: 'search_mine', token: data.tokens[u.i],
    });
  },
  list_public() {
    call('GET', '/diagrams/public', `/diagrams/public?page=${zipfIndex(10)}&size=${PAGE_SIZE}`, { op: 'list_public' });
  },
  history(data) {
    if (history.length === 0) {
      return fallback(data);
    }
    const h = pick(history);
    call('GET', '/diagrams/<id>/versions', `/diagrams/${h.id}/versions`, { op: 'history', token: data.tokens[h.o] });
  },
  version_get(data) {
    if (history.length === 0) {
      return fallback(data);
    }
    const h = pick(history);
    call('GET', '/diagrams/<id>/versions/<vid>', `/diagrams/${h.id}/versions/${h.r}`, {
      op: 'version_get', token: data.tokens[h.o],
    });
  },
  put(data) {
    const b = myBucket();
    if (b.d.length === 0) {
      return fallback(data);
    }
    const d = diagrams[b.d[zipfIndex(b.d.length)]];
    settle(d.id, d.o, data);
    const content = uniform(templates[d.c]).replace('"__NONCE__"', JSON.stringify(`bench-${exec.vu.idInTest}-${nonce++}`));
    const res = call('PUT', '/diagrams/<id>', `/diagrams/${d.id}`, {
      op: 'put', token: data.tokens[d.o],
      body: `{"title":${JSON.stringify(d.t)},"diagramType":"${d.ty}","version":${versionOf(d)},`
        + `"isPublic":${d.p},"data":${content}}`,
    });
    afterVersionedWrite(res, d.id, d.o, data);
  },
  snapshot(data) {
    const b = myBucket();
    if (b.d.length === 0) {
      return fallback(data);
    }
    const d = diagrams[b.d[zipfIndex(b.d.length)]];
    call('POST', '/diagrams/<id>/versions', `/diagrams/${d.id}/versions`, {
      op: 'snapshot', token: data.tokens[d.o], body: '{"note":"bench snapshot"}',
    });
  },
  restore(data) {
    const b = myBucket();
    if (b.h.length === 0) {
      return fallback(data);
    }
    const h = history[b.h[zipfIndex(b.h.length)]];
    versionOf(h);
    settle(h.id, h.o, data);
    const res = call('POST', '/diagrams/<id>/versions/<vid>/restore', `/diagrams/${h.id}/versions/${h.r}/restore`, {
      op: 'restore', token: data.tokens[h.o],
    });
    afterVersionedWrite(res, h.id, h.o, data);
  },
  share_create(data) {
    const b = myBucket();
    if (b.d.length === 0) {
      return fallback(data);
    }
    const d = diagrams[b.d[zipfIndex(b.d.length)]];
    call('POST', '/diagrams/<id>/shares', `/diagrams/${d.id}/shares`, {
      op: 'share_create', token: data.tokens[d.o], body: '{"role":"view"}',
    });
  },
};

for (const op of OPS) {
  if (!(op in OPERATIONS)) {
    throw new Error(`MIX có thao tác không tồn tại: ${op}`);
  }
}

export default function (data) {
  OPERATIONS[weighted(MIX)](data);
}

export function handleSummary(data) {
  return summary(data, WORKLOAD, OPS);
}
