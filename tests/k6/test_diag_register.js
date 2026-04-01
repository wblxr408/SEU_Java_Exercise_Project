import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  duration: '10s',
};

export default function () {
  const baseUrl = 'http://localhost:8080/api';
  const ts = Date.now();
  const username = `diag_${ts}_${__VU}`;

  console.log(`尝试注册: ${username}`);

  const res = http.post(
    `${baseUrl}/auth/register`,
    JSON.stringify({
      username: username,
      password: 'TestPass123',
      email: `${username}@diag.local`,
      nickname: `Diag${__VU}`,
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'DiagRegister' },
    }
  );

  console.log(`注册响应状态: ${res.status}`);
  console.log(`注册响应体: ${res.body}`);

  const ok = check(res, {
    '注册成功': (r) => r.status === 200,
    '有 token': (r) => {
      try {
        const body = JSON.parse(r.body);
        console.log(`解析结果: code=${body.code}, data=${JSON.stringify(body.data)}`);
        return !!body.data?.token;
      } catch (e) {
        console.log(`解析失败: ${e}`);
        return false;
      }
    },
  });

  console.log(`注册是否成功: ${ok}`);
}
