import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const baseUrl = 'http://localhost:8080/api';

  const payload = JSON.stringify({
    username: 'k6test99',
    password: 'TestPass123',
    email: 'test99@k6.local',
    nickname: 'Test99',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(baseUrl + '/auth/register', payload, params);

  const passed = check(res, {
    'status is 200': (r) => r.status === 200,
    'has token': (r) => {
      try {
        return !!JSON.parse(r.body).data?.token;
      } catch {
        return false;
      }
    },
  });

  console.log('Status:', res.status);
  console.log('Body:', res.body);
  console.log('Check passed:', passed);
}
