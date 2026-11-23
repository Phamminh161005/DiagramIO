// Fixture do bench/seed/gen.py sinh ra. Mỗi danh sách đã được xếp theo độ phổ biến (phần tử đầu là "nóng"
// nhất), nên chọn chỉ số theo phân bố lũy thừa là có tập sơ đồ nóng giống thực tế.
import { SharedArray } from 'k6/data';
import { FIXTURES, SIZE_CLASS, ZIPF_S } from './config.js';

function load(name) {
  return JSON.parse(open(`${FIXTURES}/${name}.json`));
}

/**
 * Danh sách fixture dùng chung giữa các VU (k6 chỉ giữ một bản trong bộ nhớ).
 * Với W3 (SIZE_CLASS=L) chỉ giữ các phần tử của sơ đồ lớn; danh sách không có cỡ thì giữ nguyên.
 */
export function fixture(name, { bySize = false } = {}) {
  const filtered = bySize && SIZE_CLASS !== '';
  return new SharedArray(filtered ? `${name}:${SIZE_CLASS}` : name, () => {
    const items = load(name);
    return filtered ? items.filter((item) => item.c === SIZE_CLASS) : items;
  });
}

/** Mẫu data cho PUT của một cỡ sơ đồ (chuỗi JSON đã serialize, có chỗ "__NONCE__" để thay). */
export function payloads(sizeClass) {
  return new SharedArray(`payloads:${sizeClass}`, () => load('payloads')[sizeClass]);
}

/**
 * Chỉ số trong 0..n-1 theo phân bố lũy thừa liên tục bị chặn, xấp xỉ Zipf với số mũ s
 * (s = 0 là đều, s = 1 thì chỉ số 0 chiếm khoảng ln2/ln(n+1) số lần chọn).
 */
export function zipfIndex(n, s = ZIPF_S) {
  if (n <= 1) {
    return 0;
  }
  const u = Math.random();
  let x;
  if (Math.abs(s - 1) < 1e-9) {
    x = Math.pow(n + 1, u);
  } else {
    const a = 1 - s;
    x = Math.pow(1 + u * (Math.pow(n + 1, a) - 1), 1 / a);
  }
  return Math.min(n - 1, Math.max(0, Math.floor(x) - 1));
}

export function pick(list) {
  if (list.length === 0) {
    throw new Error('fixture rỗng: kiểm tra FIXTURES và SIZE_CLASS');
  }
  return list[zipfIndex(list.length)];
}

export function uniform(list) {
  return list[Math.floor(Math.random() * list.length)];
}

/** Chọn một khóa theo trọng số, ví dụ {open_owner: 25, put: 8}. */
export function weighted(weights) {
  const keys = Object.keys(weights);
  let total = 0;
  for (const key of keys) {
    total += weights[key];
  }
  let r = Math.random() * total;
  for (const key of keys) {
    r -= weights[key];
    if (r < 0) {
      return key;
    }
  }
  return keys[keys.length - 1];
}
