import zipfile
z = zipfile.ZipFile("/tmp/app.jar")
names = [n for n in z.namelist() if "PositionGuard" in n]
print("names", names)
if names:
    data = z.read(names[0]).decode("latin1")
    for marker in ("MAX_HOLD", "PAPER_MAX_HOLD", "boundedElastic", "Paper guard"):
        print(marker, marker in data)
