import urllib.request
import urllib.parse

data = urllib.parse.urlencode({
    'sgf_content': '(;GM[1]FF[4]CA[UTF-8]SZ[19]KM[7.5];B[pd];W[dp];B[pp])'
}).encode()

req = urllib.request.Request(
    'http://127.0.0.1:8088/api/analyze-text',
    data=data,
    headers={'Content-Type': 'application/x-www-form-urlencoded'}
)

resp = urllib.request.urlopen(req, timeout=180)
result = resp.read().decode()
print(f"Response length: {len(result)}")
print(result[:2000])
