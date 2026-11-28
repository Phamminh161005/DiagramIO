// W1: chuỗi 5 bước của pha 1, để nối tiếp số liệu pha 1 (README mục 6).
// Mỗi VU đăng nhập một lần rồi lặp không nghỉ: tạo sơ đồ -> sửa (PUT) -> lưu phiên bản -> khôi phục
// -> tạo link chia sẻ -> mở link. Chạy với MODE=closed và VUS=10/50/100 như pha 1.
//
// Kịch bản Locust gốc của pha 1 nằm trong notebook Kaggle và không có trong repo; chuỗi request ở đây dựng lại
// theo README. Data gửi khi sửa là mẫu "tiny" (~1 KB) vì pha 1 không ghi lại kích thước (đổi bằng W1_PAYLOAD).
import exec from 'k6/execution';
import { W1_PAYLOAD } from './lib/config.js';
import { fixture, payloads, uniform } from './lib/data.js';
import { call, PASSWORD } from './lib/api.js';
import { buildOptions, summary } from './lib/run.js';

const OPS = ['login', 'create', 'put', 'snapshot', 'restore', 'share_create', 'share_open'];
const WORKLOAD = __ENV.WORKLOAD || 'w1';

const users = fixture('users');
const templates = payloads(W1_PAYLOAD);

export const options = buildOptions(OPS);

let token = null;
let iteration = 0;

export default function () {
  if (token === null) {
    const user = users[(exec.vu.idInTest - 1) % users.length];
    const res = call('POST', '/auth/login', '/auth/login', {
      op: 'login', body: JSON.stringify({ email: user.email, password: PASSWORD }), readBody: true,
    });
    if (res.status !== 200) {
      return;
    }
    token = res.json('accessToken');
  }
  iteration++;
  const name = `W1 ${exec.vu.idInTest}-${iteration}`;

  const created = call('POST', '/diagrams', '/diagrams', {
    op: 'create', token, body: JSON.stringify({ title: name, diagramType: 'ERD' }), readBody: true,
  });
  if (created.status !== 201) {
    return;
  }
  const id = created.json('id');
  const data = uniform(templates).replace('"__NONCE__"', JSON.stringify(name));
  const put = call('PUT', '/diagrams/<id>', `/diagrams/${id}`, {
    op: 'put', token,
    body: `{"title":${JSON.stringify(name)},"diagramType":"ERD","version":1,"isPublic":false,"data":${data}}`,
  });
  if (put.status !== 200) {
    return;
  }
  const snapshot = call('POST', '/diagrams/<id>/versions', `/diagrams/${id}/versions`, {
    op: 'snapshot', token, body: '{"note":"W1 snapshot"}', readBody: true,
  });
  if (snapshot.status !== 201) {
    return;
  }
  call('POST', '/diagrams/<id>/versions/<vid>/restore', `/diagrams/${id}/versions/${snapshot.json('id')}/restore`, {
    op: 'restore', token,
  });
  const share = call('POST', '/diagrams/<id>/shares', `/diagrams/${id}/shares`, {
    op: 'share_create', token, body: '{"role":"view"}', readBody: true,
  });
  if (share.status !== 201) {
    return;
  }
  call('GET', '/shares/<token>', `/shares/${share.json('shareToken')}`, { op: 'share_open' });
}

export function handleSummary(data) {
  return summary(data, WORKLOAD, OPS);
}
