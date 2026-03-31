import http.client
import json

conn = http.client.HTTPConnection("localhost", 8080, timeout=10)
headers = {"Content-Type": "application/json"}

# Test 1: hello
print("=== Test 1: GET /api/test/hello ===")
conn.request("GET", "/api/test/hello", "", headers)
resp = conn.getresponse()
print(f"Status: {resp.status}")
print(f"Headers: {dict(resp.getheaders())}")
body = resp.read().decode()
print(f"Body: {body[:500]}")
print()

# Test 2: Login
print("=== Test 2: POST /api/auth/login ===")
body = json.dumps({"username": "alice_chen", "password": "password123"})
conn.request("POST", "/api/auth/login", body, headers)
resp2 = conn.getresponse()
print(f"Status: {resp2.status}")
body2 = resp2.read().decode()
print(f"Body: {body2[:500]}")

conn.close()
