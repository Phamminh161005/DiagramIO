// Gửi request tới DiagramIO kèm tag để k6 tách số liệu theo pha (warm-up/đo), thao tác (op) và endpoint.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { API } from './config.js';
import { phaseTags } from './run.js';

export const PASSWORD = __ENV.PASSWORD || 'uet123456';

// 2xx và 304 là phản hồi đúng; mọi mã khác được tính vào http_req_failed
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 304));

// Đếm mã trạng thái trong pha đo, để báo cáo biết lỗi là loại gì
const statusCounters = {
  304: new Counter('resp_304'),
  409: new Counter('resp_409'),
  '4xx': new Counter('resp_4xx_other'),
  '5xx': new Counter('resp_5xx'),
  none: new Counter('resp_no_response'),
};

function countStatus(status) {
  if (status === 304 || status === 409) {
    statusCounters[status].add(1);
  } else if (status >= 500) {
    statusCounters['5xx'].add(1);
  } else if (status >= 400) {
    statusCounters['4xx'].add(1);
  } else if (status === 0) {
    statusCounters.none.add(1);
  }
}

/**
 * Một request. route là mẫu đường dẫn không chứa id (ví dụ "/diagrams/<id>") để k6 gom số liệu theo endpoint
 * thay vì theo từng URL. Body chỉ được đọc khi readBody = true (mặc định k6 bỏ body để đỡ CPU).
 */
export function call(method, route, path, { op, body = null, token = null, shareToken = null, ifNoneMatch = null,
  readBody = false } = {}) {
  const tags = phaseTags();
  tags.op = op;
  tags.name = `${method} ${route}`;
  const headers = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (shareToken) {
    headers['X-Share-Token'] = shareToken;
  }
  if (ifNoneMatch) {
    headers['If-None-Match'] = ifNoneMatch;
  }
  if (body !== null) {
    headers['Content-Type'] = 'application/json';
  }
  const params = { headers, tags };
  if (readBody) {
    params.responseType = 'text';
  }
  const res = http.request(method, `${API}${path}`, body, params);
  if (tags.phase === 'measure') {
    countStatus(res.status);
  }
  return res;
}

/** Đăng nhập trước khi đo (trong setup), song song từng nhóm nhỏ; trả access token theo thứ tự users. */
export function loginAll(users) {
  const tokens = [];
  const batch = 10;
  for (let start = 0; start < users.length; start += batch) {
    const requests = [];
    for (let i = start; i < Math.min(users.length, start + batch); i++) {
      requests.push(['POST', `${API}/auth/login`, JSON.stringify({ email: users[i].email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' }, responseType: 'text',
        tags: { phase: 'setup', op: 'login', name: 'POST /auth/login' },
      }]);
    }
    const responses = http.batch(requests);
    for (let k = 0; k < responses.length; k++) {
      const res = responses[k];
      if (res.status !== 200) {
        throw new Error(`đăng nhập ${users[start + k].email} thất bại: ${res.status} ${res.body}`);
      }
      tokens.push(res.json('accessToken'));
    }
  }
  return tokens;
}
