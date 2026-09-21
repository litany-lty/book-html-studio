package studio.bookhtml.service;

import studio.bookhtml.domain.Page;

/**
 * F03/R08：处理结果分类与页面数据分开。
 * 分类只描述本次识别产出的证据状态，不代替 READY/FAILED 执行状态；
 * 老 JSON 没有分类字段时，绝不能被自动认定为空白。
 */
public record ProcessingResult(Page page, ProcessingResult.Category category) {
    public enum Category {
        /** 有可用文字。 */
        TEXT,
        /** 图像证据确实近空白，成功的空结果。 */
        BLANK_CONFIRMED,
        /** 合法纯视觉页（插图/表格/公式），保留原图与有效区域。 */
        VISUAL_ONLY,
        /** 非空白扫描页但 OCR 为空：未解决，必须失败或候选，绝不能成功空白化。 */
        OCR_EMPTY_UNRESOLVED,
        /** 处理失败。 */
        FAILED
    }
}
