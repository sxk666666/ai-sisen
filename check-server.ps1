try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8088/' -TimeoutSec 3 -UseBasicParsing
    "Status: $($r.StatusCode)"
} catch {
    "Error: $($_.Exception.Message)"
}
