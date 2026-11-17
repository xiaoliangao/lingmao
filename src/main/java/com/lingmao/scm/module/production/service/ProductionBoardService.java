package com.lingmao.scm.module.production.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.production.dto.BoardCards;
import com.lingmao.scm.module.production.dto.BoardFactoryRow;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;

import java.util.List;

/** 生产看板（docs/api/05 §3.7）：都照数据范围，只看已下单、生产中（本月回货除外） */
public interface ProductionBoardService {

    BoardCards cards(BoardQuery query);

    PageResult<ProductionRowVO> orders(BoardQuery query);

    List<BoardFactoryRow> factories(BoardQuery query);
}
