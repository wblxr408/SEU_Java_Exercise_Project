import http.client
import json

conn = http.client.HTTPConnection("localhost", 8080, timeout=10)
headers = {"Content-Type": "application/json"}

# Test /api/auth/register (with /api prefix)
body = json.dumps({"username": "test999", "password": "Test123", "email": "test999@test.com", "nickname": "Test999"})
conn.request("POST", "/api/auth/register", body, headers)
resp = conn.getresponse()
print(f"WITH_API /api/auth/register: status={resp.status} body={resp.read().decode()[:300]}")

# Test /api/auth/login (login endpoint)
body2 = json.dumps({"username": "test999", "password": "Test123"})
conn.request("POST", "/api/auth/login", body2, headers)
resp2 = conn.getresponse()
print(f"LOGIN /api/auth/login: status={resp2.status} body={resp2.read().decode()[:300]}")

conn.close()
