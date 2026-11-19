package com.lingmao.scm.module.order.dto;

/**
 * 审核对话框里一个款（docs/api/02 §3.8，board 拼 OrderReview 用）：审核后会锁哪一版生产 BOM、能不能审，默认加工厂。
 *
 * @param techPackId       现在生效的生产 BOM；没有是 null
 * @param problem          不能审的原因，和提交、审核时的检查一样（几条用"；"连）；能审是 null
 * @param defaultFactoryId 款式的默认加工厂；没设是 null
 */
public record OrderReviewStyle(long styleId, String styleCode, String styleName, Long techPackId, Integer version, String problem,
                               Long defaultFactoryId) {
}
