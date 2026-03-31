import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  duration: '2s',
};

export default function () {
  // 测试1: 直接 IP + 端口
  const r1 = http.get('http://127.0.0.1:8080/');
  console.log('127.0.0.1:8080/ => status:', r1.status, 'time:', r1.timings.duration, 'body:', r1.body.substring(0, 100));

  // 测试2: localhost
  const r2 = http.get('http://localhost:8080/');
  console.log('localhost:8080/ => status:', r2.status, 'time:', r2.timings.duration, 'body:', r2.body.substring(0, 100));
}
