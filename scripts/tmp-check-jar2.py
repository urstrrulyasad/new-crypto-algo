import zipfile, shutil, subprocess
subprocess.check_call(["docker", "cp", "crypto-algo-backend-1:/app/app.jar", "/tmp/app-live.jar"])
z = zipfile.ZipFile("/tmp/app-live.jar")
data = z.read("BOOT-INF/classes/com/cryptoalgo/backend/trading/PortfolioController.class")
for s in [b"enrichPosition", b"unrealizedPnl", b"markPrice", b"lastPrice"]:
    print(s.decode(), s in data)
rec = z.read("BOOT-INF/classes/com/cryptoalgo/backend/trading/OrderReconciliationService.class")
print("futures-only reconcile", b"FUTURES".encode() in rec)
