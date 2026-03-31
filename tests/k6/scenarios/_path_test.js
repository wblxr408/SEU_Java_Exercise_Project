import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080';

export const options = {
  vus: 1,
  duration: '3s',
};

export default function () {
  const payload = JSON.stringify({ username: 'alice_chen', password: 'password123' });

  // 1. 直接测根路径
  const r1 = http.get(BASE_URL + '/');
  console.log('GET / status:', r1.status, 'body:', r1.body.substring(0, 200));

  // 2. 测 /api/test/hello
  const r2 = http.get(BASE_URL + '/api/test/hello');
  console.log('GET /api/test/hello status:', r2.status, 'body:', r2.body.substring(0, 200));

  // 3. 测登录（不带 /api 前缀，看后端返回什么）
  const r3 = http.post(BASE_URL + '/auth/login', payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('POST /auth/login status:', r3.status, 'body:', r3.body.substring(0, 200));

  // 4. 测 /api/auth/login
  const r4 = http.post(BASE_URL + '/api/auth/login', payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  console.log('POST /api/auth/login status:', r4.status, 'body:', r4.body.substring(0, 200));
}
