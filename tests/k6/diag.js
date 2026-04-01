import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  duration: '10s',
};

export default function () {
  const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';
  console.log('Testing BASE_URL:', BASE_URL);

  // 测试注册
  const regBody = JSON.stringify({
    username: 'k6debugtest',
    password: 'TestPass123',
    email: 'debugtest@k6.local',
    nickname: 'DebugTest',
  });

  const regRes = http.post(`${BASE_URL}/auth/register`, regBody, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'Register' },
  });

  console.log('Register status:', regRes.status);
  console.log('Register body:', regRes.body);

  const ok = check(regRes, {
    '注册成功': (r) => r.status === 200,
  });

  if (!ok) {
    console.error('注册失败!');
    return;
  }

  // 解析 token
  let token = null;
  try {
    const body = JSON.parse(regRes.body);
    token = body.data?.token;
    console.log('Got token:', token ? 'YES' : 'NO');
  } catch (e) {
    console.error('解析响应失败:', e.message);
    return;
  }

  if (!token) {
    console.error('Token 为空!');
    return;
  }

  // 测试发帖
  const postRes = http.post(
    `${BASE_URL}/post/create`,
    JSON.stringify({ content: 'Test post' }),
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      tags: { name: 'CreatePost' },
    }
  );

  console.log('Post status:', postRes.status);
  console.log('Post body:', postRes.body);
}
