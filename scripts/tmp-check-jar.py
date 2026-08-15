import zipfile
z = zipfile.ZipFile("/app/app.jar")
data = z.read("BOOT-INF/classes/com/cryptoalgo/backend/strategy/PaperEvaluationService.class")
for s in [
    b"never purge",
    b"staying on paper",
    b"Archiving unpromotable",
    b"Paper catchup starting",
    b"Paper OK for",
]:
    print(s.decode(), s in data)
