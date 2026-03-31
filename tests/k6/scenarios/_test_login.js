import http from 'k6/http';
import { check } from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';

export const options = {
  vus: 1,
  duration: '3s',
  thresholds: {},
};

export default function () {
  const payload = JSON.stringify({ username: 'alice_chen', password: 'password123' });
  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'AuthLogin' },
  };

  console.log('Attempting login to:', `${BASE_URL}/auth/login`);

  const res = http.post(`${BASE_URL}/auth/login`, payload, params);

  console.log('Status:', res.status);
  console.log('Body:', res.body);
  console.log('Error:', res.error);

  const ok = check(res, {
    '登录成功 (200)': (r) => r.status === 200,
    '响应包含 token': (r) => {
      try {
        const body = JSON.parse(r.body);
        console.log('Parsed body:', JSON.stringify(body));
        return !!body.data?.token;
      } catch (e) {
        console.log('Parse error:', e.message);
        return false;
      }
    },
  });

  console.log('Check ok:', ok);

  if (ok && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      console.log('Token:', body.data?.token ? 'FOUND' : 'NOT FOUND');
      console.log('UserInfo:', JSON.stringify(body.data?.userInfo));
    } catch (e) {
      console.log('Second parse error:', e.message);
    }
  }
}
