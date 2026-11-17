package com.lingmao.scm.module.techpack.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.techpack.dto.CostSummary;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TechPackCostServiceImpl implements TechPackCostService {

    private final TechPackMapper techPackMapper;
    private final PackOwners packOwners;
    private final CostCalculator costCalculator;
    private final CategoryService categoryService;
    private final AttachmentService attachmentService;

    @Override
    public TechPackCostVO cost(long id, Long colorId) {
        TechPack pack = techPackMapper.selectById(id);
        if (pack == null) {
            throw new BizException(404, "这一版资料包不存在，可能已经被删除了");
        }
        Ctx ctx = packOwners.context(packOwners.of(pack));
        if (colorId != null && !ctx.colorIds().contains(colorId)) {
            throw new BizException("这个颜色不是款式现在启用的颜色，请刷新后再选");
        }
        return costCalculator.compute(pack, ctx, colorId != null ? colorId : ctx.firstColorId(), true);
    }

    @Override
    public PageResult<StyleCostRowVO> styleCosts(StyleCostQuery query) {
        IPage<StyleCostRowVO> page = techPackMapper.selectStyleCostPage(query.toPage(), query, codesIn(query.getKeyword()),
                withDescendants(query.getCategoryId()));
        List<StyleCostRowVO> rows = page.getRecords();
        if (!rows.isEmpty()) {
            Map<Long, PackOwner> owners = packOwners.styles(rows.stream().map(StyleCostRowVO::getStyleId).toList());
            Map<Long, TechPack> packs = CostCalculator.byId(
                    techPackMapper.selectByIds(rows.stream().map(StyleCostRowVO::getTechPackId).toList()), TechPack::getId);
            Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE,
                    rows.stream().map(StyleCostRowVO::getStyleId).toList(), Attachment.IMAGE);
            for (StyleCostRowVO row : rows) {
                row.setImage(images.get(row.getStyleId()));
                PackOwner owner = owners.get(row.getStyleId());
                TechPack pack = packs.get(row.getTechPackId());
                if (owner == null || pack == null) {
                    continue;
                }
                Ctx ctx = packOwners.context(owner);
                CostSummary s = costCalculator.compute(pack, ctx, ctx.firstColorId(), false).getSummary();
                row.setFabric(s.fabric());
                row.setTrim(s.trim());
                row.setLabor(s.labor());
                row.setOther(s.other());
                row.setTotal(s.total());
                row.setRatio(Num.ratio(row.getTagPrice(), s.total()));
            }
        }
        return PageResult.of(page);
    }

    /** 关键字有空格 = 几个款号一起查（和款式列表一样）；返回大写的款号，没有空格是 null */
    static List<String> codesIn(String keyword) {
        if (keyword == null || !keyword.strip().matches(".*\\s.*")) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(keyword.strip().split("\\s+"))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /** 品类连同下级的 id（品类就几十个，整棵取了在内存里找）；不筛是 null */
    private List<Long> withDescendants(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Deque<TreeNode> stack = new ArrayDeque<>(categoryService.tree());
        while (!stack.isEmpty()) {
            TreeNode node = stack.pop();
            if (categoryId.equals(node.getId())) {
                List<Long> ids = new ArrayList<>();
                Deque<TreeNode> sub = new ArrayDeque<>(List.of(node));
                while (!sub.isEmpty()) {
                    TreeNode n = sub.pop();
                    ids.add(n.getId());
                    sub.addAll(n.getChildren());
                }
                return ids;
            }
            stack.addAll(node.getChildren());
        }
        return List.of(categoryId);   // 不在树里（刚被删了）：照样按它筛，查出来是空的
    }
}
