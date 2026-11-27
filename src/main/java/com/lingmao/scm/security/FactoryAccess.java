package com.lingmao.scm.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工厂账号（RFC 019 §7）能调这个接口。没标的工厂账号一律 403（FactoryScopeInterceptor）：以后新加的接口默认工厂调不了。
 * 标了还要接口自己限住只看本厂的：生产单靠 ProductionSupport.visible，工厂的列表、详情按 CallerScope.factoryId() 查。
 * 现在标了的：看自己（/api/auth/me）、改密码、退出、刷新登录、版本、上传图片、本厂生产单的列表和详情、报进度、看节点的汇报记录、登记裁床数
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FactoryAccess {
}
