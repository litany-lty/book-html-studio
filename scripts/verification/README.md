# 合成证据夹具

本目录提供与生产数据无关、可重复生成的测试夹具，供 A1 及后续批次的
HTTP / 浏览器 / 离线回归使用。禁止把私有 PDF、真实 OCR 缓存或 API key 放入此处。

## SeedBook.java：3 页合成书

生成一本 3 页空白 PDF 书籍，第 1 页含两个文本块（`b1` 带 `issue-1` 疑点），
第 2 页含一个文本块（`c1`，无疑点），第 3 页为 PENDING。书籍 UUID 固定，
便于跨轮复现同一夹具。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
CP=$(cat /tmp/cp.txt):target/classes
javac -cp "$CP" -d /tmp/seed-classes scripts/verification/SeedBook.java
rm -rf /tmp/seed-data && mkdir -p /tmp/seed-data
java -cp "/tmp/seed-classes:$CP" SeedBook /tmp/seed-data
BOOK_DATA_DIR=/tmp/seed-data PORT=18767 java -Xmx768m -jar target/book-html-studio.jar
```

第 2 页加疑点（离线双页并发场景需要两页各有一个 issue）：

```bash
B=http://127.0.0.1:18767/api/books/aaaaaaaa-1111-1111-1111-111111111111
curl -s -X PUT $B/pages/2 -H 'Content-Type: application/json' -d \
  '{"blocks":[{"id":"c1","type":"text","order":0,"bbox":[0.1,0.1,0.5,0.06], \
  "writingMode":"horizontal-tb","original":"寅卯辰巳午未","simplified":"寅卯辰巳午未", \
  "confidence":0.9,"uncertain":true,"reviewed":false,"headingLevel":null, \
  "source":"local","sourceIds":["c1"],"suggestion":null,"sourceRect":[10,20,50,10], \
  "issues":[{"id":"issue-2","kind":"suspected","start":0,"end":2, \
  "simplifiedStart":0,"simplifiedEnd":2,"reason":"合成疑点2","resolved":false, \
  "replacement":"","inferredText":null}]}],"reviewed":false,"revision":0}'
```
