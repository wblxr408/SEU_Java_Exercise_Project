import socket
import http.client
import json

# Check what localhost resolves to
try:
    print("localhost resolves to:", socket.gethostbyname("localhost"))
except:
    print("Cannot resolve localhost")

# Try with localhost (main service endpoint)
conn = http.client.HTTPConnection("localhost", 8080, timeout=10)
headers = {"Content-Type": "application/json"}
body = json.dumps({"username": "test_tcp", "password": "Test123", "email": "testtcp@test.com", "nickname": "TestTCP"})
conn.request("POST", "/api/auth/register", body, headers)
resp = conn.getresponse()
print(f"TCP/127.0.0.1 /api/auth/register: status={resp.status} body={resp.read().decode()[:200]}")
conn.close()

# Try with localhost (same as 127.0.0.1 for local access)
conn2 = http.client.HTTPConnection("localhost", 8080, timeout=10)
conn2.request("POST", "/api/auth/login", json.dumps({"username": "test_tcp", "password": "Test123"}), headers)
resp2 = conn2.getresponse()
print(f"TCP/localhost /api/auth/login: status={resp2.status} body={resp2.read().decode()[:200]}")
conn2.close()
