import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  duration: '5s',
};

export default function () {
  const baseUrl = 'http://localhost:8080/api';
  const ts = Date.now();
  const username = `diag_${ts}_${__VU}`;

  const payload = JSON.stringify({
    username: username,
    password: 'TestPass123',
    email: `${username}@diag.local`,
    nickname: 'DiagTest',
  });

  const res = http.post(`${baseUrl}/auth/register`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  console.log(`状态: ${res.status}`);
  console.log(`body: ${res.body}`);

  check(res, { 'ok': () => res.status === 200 });

  sleep(1);
}
