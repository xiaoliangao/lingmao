package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.OperLogQuery;
import com.lingmao.scm.module.system.entity.SysOperLog;
import com.lingmao.scm.module.system.mapper.SysOperLogMapper;
import com.lingmao.scm.module.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OperLogServiceImpl implements OperLogService {

    private final SysOperLogMapper sysOperLogMapper;

    @Override
    public PageResult<SysOperLog> page(OperLogQuery query) {
        LambdaQueryWrapper<SysOperLog> wrapper = new LambdaQueryWrapper<SysOperLog>()
                .like(StringUtils.hasText(query.getUsername()), SysOperLog::getUsername, query.getUsername())
                .eq(StringUtils.hasText(query.getModule()), SysOperLog::getModule, query.getModule())
                .eq(query.getSuccess() != null, SysOperLog::getSuccess, query.getSuccess())
                .orderByDesc(SysOperLog::getId);
        return PageResult.of(sysOperLogMapper.selectPage(query.toPage(), wrapper));
    }
}
