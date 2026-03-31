import urllib.request
import json

url = 'http://localhost:8080/api/auth/login'
data = json.dumps({'username': 'alice_chen', 'password': 'password123'}).encode()
req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(req) as resp:
        print(f'Status: {resp.status}')
        print(f'Body: {resp.read().decode()}')
except Exception as e:
    print(f'Error: {e}')
    if hasattr(e, 'read'):
        print(f'Response: {e.read().decode()}')
