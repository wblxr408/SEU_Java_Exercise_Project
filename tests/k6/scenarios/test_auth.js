import { register, login, BASE_URL } from '../modules/auth.js';

export const options = {
  vus: 1,
  duration: '5s',
};

export default function () {
  // 直接用 auth.js 的 register
  const result = register('debug_user_' + Date.now(), 'TestPass123', 'DebugUser');
  console.log('register result token:', result.token ? 'OK' : 'NULL');
  console.log('register result status:', result.response.status);
  console.log('register result body:', result.response.body);
}
