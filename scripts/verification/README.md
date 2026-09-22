# 合成证据夹具

本目录提供与生产数据无关、可重复生成的测试夹具，供 A1 及后续批次的
HTTP / 浏览器 / 离线回归使用。禁止把私有 PDF、真实 OCR 缓存或 API key 放入此处。

## 书架管理

`LibraryManagementTest` 用临时 BookStore 核查旧书兼容、改名与归档持久化、正在处理时拒绝归档、恢复后可重新预约处理，以及页统计更新不覆盖书架信息。`cdp_library.py` 只接受独立本地端口的 `ReadingWindowQaServer` 两本合成书；测试会对合成书改名、归档、恢复，并验证搜索、用量入口和 390px 布局。先按下节启动新数据目录，再执行 `LIBRARY_OUT=/tmp/library-check python3 scripts/verification/cdp_library.py`；不能指向 18765 用户服务。

## 随读窗口与快速跳页

`node scripts/verification/probe_reading_window.js` 检查本地邻页预取上限、迟到响应隔离、序号/停止及连续页码输入不被上一请求阻塞；`ReadingWindowServiceTest` 使用模拟处理器和真实临时 BookStore 检查优先序、1 秒停留、撤换队列、租期、互斥与人工内容保护。

`ReadingWindowQaServer.java` 只在测试 classpath 中编译，不进入应用 JAR。它建立 60 页合成书及既有 3 页 SeedBook，用门控 Mockito `PageProcessor` 代替所有真实识别；生产调度、HTTP API 和条件保存保持不变。每个模拟调用必须由验收脚本明确释放，因此能确定旧请求收尾、新中心优先的真实先后次序。模拟结果不是 OCR 准确率、延迟或实际费用证据。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
BOOK_WINDOW_TMP=$(mktemp -d /tmp/book-reading-qa.XXXXXX)
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$BOOK_WINDOW_TMP/classpath.txt"
BOOK_WINDOW_CP="$(cat "$BOOK_WINDOW_TMP/classpath.txt"):target/classes"
mkdir -p "$BOOK_WINDOW_TMP/classes"
javac -cp "$BOOK_WINDOW_CP" -d "$BOOK_WINDOW_TMP/classes" scripts/verification/SeedBook.java scripts/verification/ReadingWindowQaServer.java
env -i PATH=/usr/bin:/bin "$JAVA_HOME/bin/java" -Xmx640m -cp "$BOOK_WINDOW_TMP/classes:$BOOK_WINDOW_CP" ReadingWindowQaServer "$BOOK_WINDOW_TMP/data" 18767
```

在另一终端执行 `READING_OUT=/tmp/reading-window-check python3 scripts/verification/cdp_reading_window.py`。脚本只接受独立本地端口、匹配身份且尚未处理过的合成书，验证连续跳页、停留不足 1 秒零调用、旧页收尾后新中心优先、逐页呈现、停止不继续派发、脏稿不被新结果覆盖、过期缓存重验证及 390px 布局。复跑应新建临时目录；结束后用 Ctrl+C 停止自己启动的夹具服务，不停止日常 18765 服务。也可对该夹具运行下方 `cdp_reader_ux.py`，复验保存与冲突链路。

## 校对工作台 UI 回归

`node scripts/verification/probe_editor_evidence.js` 验证疑点证据异步隔离、真宽高比回退、逐字/区域定位区分，以及切换疑点时的文字草稿与有意清空。包含短字数但多行 OCR/候选折叠和编辑后紧接确认操作的回归。

用独立端口的 SeedBook 运行 `UX_SERVER=http://127.0.0.1:18767 UX_OUT=/tmp/reader-ux-check python3 scripts/verification/cdp_reader_ux.py`，复用已有 `cdpdrive.py` 与本机 Chrome。脚本只接受本地非 18765 端口，先检查合成书身份；会写入合成书的校对标记，不能指向日常数据目录。验证桌面/390px、字体预览与 ESC 回焦、更多菜单、在途保存、跨页旧响应隔离、在途新增草稿、真实 revision 冲突及主要触控尺寸，不调用 OCR 或 JEV。

合成书不代表真实图像布局。发版前还应在隔离副本中检查长竖栏、多行候选：图片显示宽高比与自然宽高比一致，可靠坐标才有疑字红框，原稿预览限高且可滚动，编辑与确认操作均在保存栏上方。建议同时截取桌面与手机，并等待抽屉过渡结束后测量。

## 阅读字体静态回归

在仓库根目录执行 `node scripts/verification/probe_reader_fonts.js`，检查内置资源的 SHA256、WOFF2 文件头、OFL 许可、CSS 引用完整且无外部 URL，并验证字体白名单、偏好持久化、存储被拒绝及控件不会误替换 HTML 根节点。

`ExportServiceTest` 另外核对实际 ZIP 中的全部字体字节和本地引用。验证浏览器时，在线与 `file://` 离线书都需选择 Noto、文楷和 JetBrains Mono，检查正文计算字体、字体请求无 404、中文回退，以及刷新后选择保留；不能仅凭下拉选项存在便认定字形已加载。字体下载脚本 `scripts/fetch-reader-fonts.mjs` 仅供维护者更新资产，正常构建不联网下载字体。

## SeedUsage.java：仅供费用界面的合成记录

在一份全新 `SeedBook` 夹具上、启动服务之前，可按相同 classpath 编译并运行 `SeedUsage <数据目录>`。它通过生产 `UsageLedger` API 生成 56 条合成记录：51 次请求、5 次缓存复用，参考估算 CNY 0.628 与 USD 0.014，11 次费用未知。**它不会调用供应商，数字不是实际花费或真实用量证据。** 已有设置或账本时拒绝运行，禁止指向用户日常数据。可用于核查 50 条一页的游标分页、币种分列、未知用量、刷新与重启持久化。

用该数据在独立端口启动后，执行 `node scripts/verification/probe_settings_usage.mjs http://127.0.0.1:18767`。探针先验证合成书身份，再检查配置脱敏、CSRF/异源/Host 拒绝、费用缺口与 50+6 条游标分页；它拒绝日常工具端口 18765，不发送 OCR 或模型请求。

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
