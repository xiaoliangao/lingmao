package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 记、改一条质检记录
 *
 * @param version      改的时候带打开时拿到的版本号；新建不用
 * @param kind         新建时必填：INCOMING 来料检验 / PRE_PRODUCTION 产前样确认 / MIDDLE 中检 / FINAL 尾期验货；改的时候不看
 * @param sourceId     新建时必填，按 kind：采购入库单 id / 生产单 id / 生产单 id / 回货单 id；改的时候不看
 * @param inspectDate  检验日期，不能晚于今天
 * @param sampleQty    抽检数；产前样可以不填，别的必填
 * @param defectQty    不合格数，不能大于抽检数；不填当 0
 * @param result       结论：PASS 合格 / CONCESSION 让步接收 / FAIL 不合格；产前样只有合格、不合格
 * @param defectTypes  问题类型（字典 qc_defect_type 的值，多选，可以不选）
 * @param note         问题：不是合格的必填
 * @param photos       照片（先上传拿到地址），按顺序；不传是没有
 * @param deductAmount 质量扣款：填了就同时登记一笔扣款（月底进对账单），要扣款登记的权限；不是合格的才能扣。
 *                     改的时候没有扣款权限的人传什么都不看，原来的扣款不动
 */
public record QcReq(Integer version,
                    QcKind kind,
                    Long sourceId,
                    @NotNull(message = "检验日期必填") LocalDate inspectDate,
                    @DecimalMin(value = "0", message = "抽检数不能是负数") @Digits(integer = 11, fraction = 3, message = "抽检数最多三位小数")
                    BigDecimal sampleQty,
                    @DecimalMin(value = "0", message = "不合格数不能是负数") @Digits(integer = 11, fraction = 3, message = "不合格数最多三位小数")
                    BigDecimal defectQty,
                    @NotNull(message = "结论必选") QcResult result,
                    @Size(max = 20, message = "问题类型最多选 20 个") List<String> defectTypes,
                    @Size(max = 1000, message = "问题最多 1000 个字") String note,
                    @Valid @Size(max = 9, message = "照片最多 9 张") List<AttachmentReq> photos,
                    @DecimalMin(value = "0.01", message = "扣款金额要大于 0")
                    @Digits(integer = 12, fraction = 2, message = "扣款金额最多两位小数") BigDecimal deductAmount) {
}
