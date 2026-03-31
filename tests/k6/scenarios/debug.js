import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080';

export const options = {
  vus: 1,
  duration: '5s',
  thresholds: {},
};

export default function () {
  // Debug: 直接测 hello
  const res = http.get(`${BASE_URL}/api/test/hello`);
  console.log('hello status:', res.status);
  console.log('hello body:', res.body);
  console.log('hello error:', res.error);

  // Debug: 测登录
  const loginPayload = JSON.stringify({ username: 'alice_chen', password: 'password123' });
  const loginRes = http.post(
    `${BASE_URL}/api/auth/login`,
    loginPayload,
    { headers: { 'Content-Type': 'application/json' } }
  );
  console.log('login status:', loginRes.status);
  console.log('login body:', loginRes.body);
  console.log('login error:', loginRes.error);
}
