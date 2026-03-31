import http.client
import json

headers = {"Content-Type": "application/json"}

# Test 1: localhost - first connection
print("=== Test 1: localhost first connection ===")
conn1 = http.client.HTTPConnection("localhost", 8080, timeout=10)
body = json.dumps({"username": "test_localhost", "password": "Test123", "email": "testlh@test.com", "nickname": "TestLH"})
conn1.request("POST", "/api/auth/register", body, headers)
resp1 = conn1.getresponse()
print(f"Status: {resp1.status}")
print(f"Body: {resp1.read().decode()[:200]}")
conn1.close()

# Test 2: 127.0.0.1 - separate connection
print("\n=== Test 2: 127.0.0.1 ===")
conn2 = http.client.HTTPConnection("127.0.0.1", 8080, timeout=10)
conn2.request("POST", "/api/auth/register", body, headers)
resp2 = conn2.getresponse()
print(f"Status: {resp2.status}")
print(f"Body: {resp2.read().decode()[:200]}")
conn2.close()
