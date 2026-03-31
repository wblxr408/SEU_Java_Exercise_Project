import http.client
import json

conn = http.client.HTTPConnection("localhost", 8080, timeout=10)
headers = {"Content-Type": "application/json"}

# Test 1: Register
body = json.dumps({"username": "k6test001", "password": "K6Test123", "email": "k6test001@test.com", "nickname": "K6Test001"})
conn.request("POST", "/api/auth/register", body, headers)
resp = conn.getresponse()
print(f"REGISTER: status={resp.status} body={resp.read().decode()[:500]}")

# Test 2: Login
body2 = json.dumps({"username": "alice_chen", "password": "password123"})
conn.request("POST", "/api/auth/login", body2, headers)
resp2 = conn.getresponse()
print(f"LOGIN: status={resp2.status} body={resp2.read().decode()[:500]}")

# Test 3: Hello
conn.request("GET", "/api/test/hello")
resp3 = conn.getresponse()
print(f"HELLO: status={resp3.status} body={resp3.read().decode()[:500]}")

conn.close()
