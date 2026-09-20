package studio.bookhtml.service;

import studio.bookhtml.config.AppProperties;
import java.nio.file.Path;

final class TestConfigs {
    private TestConfigs(){}
    static AppProperties config(Path data,String qwenKey,String miniKey){return new AppProperties(data,300,5000,2400,"tesseract",qwenKey,"qwen3.5-ocr","https://dashscope.aliyuncs.com/api/v1",5,miniKey,"MiniMax-M3","https://api.minimax.cn/v1",5,true);}
}
